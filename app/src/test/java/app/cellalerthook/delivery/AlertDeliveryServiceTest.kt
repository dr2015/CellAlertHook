package app.cellalerthook.delivery

import app.cellalerthook.FakeClock
import app.cellalerthook.FakeStore
import app.cellalerthook.RETAIL_BUILD
import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.created
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.domain.DeliveryStatus
import app.cellalerthook.newProcessor
import app.cellalerthook.providerObservation
import app.cellalerthook.uiObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class AlertDeliveryServiceTest {
    private val store = FakeStore()
    private val clock = FakeClock()
    private val processor = newProcessor(store, clock)
    private val transport = FakeTransport()
    private val scheduled = mutableListOf<Long>()

    private var config = ReceiverConfig(
        serverHost = "10.0.0.5",
        serverPort = 8443,
        retryBaseSeconds = 5,
        retryMaxSeconds = 300,
    )

    private class FakeTransport : AlertTransport {
        val requests = mutableListOf<TransportRequest>()
        var respond: (TransportRequest) -> TransportResponse = ::acknowledge

        override suspend fun post(request: TransportRequest): TransportResponse {
            requests += request
            return respond(request)
        }

        companion object {
            private val eventIdHeader = "X-CellAlertHook-Event-Id"

            fun acknowledge(request: TransportRequest) = TransportResponse(
                200,
                """{"ack":true,"eventId":"${request.headers[eventIdHeader]}","revision":1,"duplicate":false}""",
            )
        }
    }

    private fun service(scope: CoroutineScope) = AlertDeliveryService(
        store = store,
        transport = transport,
        config = { config },
        authToken = { "secret" },
        log = { _, _, _ -> },
        provenance = RETAIL_BUILD,
        scheduler = { scheduled += it },
        scope = scope,
        clock = clock.read,
        random = Random(1),
    )

    @Test
    fun `an acknowledged event is delivered and leaves the queue`() = runTest {
        val event = processor.process(providerObservation()).created()
        val summary = service(this).drain()

        assertEquals(1, summary.delivered)
        val stored = store.events[event.eventId]!!
        assertEquals(DeliveryStatus.DELIVERED, stored.deliveryStatus)
        assertEquals(1, stored.deliveryAttempts)
        assertEquals(200, stored.lastHttpStatus)
        assertNotNull(stored.deliveredAtMs)
        assertEquals(EvidenceKind.SERVER_DELIVERY to EvidenceProvenance.LIVE_DEVICE, store.evidence.single())
    }

    @Test
    fun `the request carries the credential, the identifiers and the production path`() = runTest {
        val event = processor.process(providerObservation()).created()
        service(this).drain()

        val request = transport.requests.single()
        assertEquals("https://10.0.0.5:8443/api/emergency/alerts", request.url)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertEquals(event.eventId, request.headers["X-CellAlertHook-Event-Id"])
        assertEquals("false", request.headers["X-CellAlertHook-Simulation"])
    }

    @Test
    fun `a simulation goes to the test path and never the production path`() = runTest {
        processor.process(uiObservation(simulated = true))
        service(this).drain()

        val request = transport.requests.single()
        assertEquals("https://10.0.0.5:8443/api/emergency/test-alerts", request.url)
        assertEquals("true", request.headers["X-CellAlertHook-Simulation"])
        assertEquals(EvidenceKind.SERVER_DELIVERY to EvidenceProvenance.SIMULATED, store.evidence.single())
    }

    @Test
    fun `a simulation is blocked if the paths were ever made equal`() = runTest {
        config = config.copy(testAlertsPath = config.alertsPath)
        val event = processor.process(uiObservation(simulated = true)).created()
        service(this).drain()

        assertTrue(transport.requests.isEmpty())
        assertEquals(DeliveryStatus.BLOCKED, store.events[event.eventId]!!.deliveryStatus)
    }

    @Test
    fun `a network failure keeps the event and schedules a retry`() = runTest {
        transport.respond = { throw IOException("no route") }
        val event = processor.process(providerObservation()).created()
        val summary = service(this).drain()

        assertEquals(1, summary.failed)
        val stored = store.events[event.eventId]!!
        assertEquals(DeliveryStatus.PENDING, stored.deliveryStatus)
        assertEquals("network:IOException", stored.lastDeliveryError)
        assertTrue(stored.nextAttemptAtMs > clock.nowMs)
        assertEquals(1, scheduled.size)
    }

    @Test
    fun `a non network failure also keeps the event`() = runTest {
        transport.respond = { throw IllegalArgumentException("bad certificate") }
        val event = processor.process(providerObservation()).created()
        service(this).drain()
        assertEquals(DeliveryStatus.PENDING, store.events[event.eventId]!!.deliveryStatus)
    }

    @Test
    fun `an event is not retried before its backoff expires`() = runTest {
        transport.respond = { throw IOException("down") }
        processor.process(providerObservation())
        val service = service(this)
        service.drain()
        service.drain()
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `delivery resumes when the server returns and the event arrives once`() = runTest {
        transport.respond = { throw IOException("down") }
        val event = processor.process(providerObservation()).created()
        val service = service(this)
        service.drain()

        transport.respond = FakeTransport::acknowledge
        clock.advance(10 * 60_000)
        val summary = service.drain()

        assertEquals(1, summary.delivered)
        assertEquals(DeliveryStatus.DELIVERED, store.events[event.eventId]!!.deliveryStatus)
        assertEquals(2, store.attempts.size)
    }

    @Test
    fun `a 2xx without a valid acknowledgment does not count as delivered`() = runTest {
        val cases = listOf("", "not json", """{"ack":false}""", """{"ack":true,"eventId":"someone-else"}""")
        for (body in cases) {
            transport.respond = { TransportResponse(200, body) }
            val event = processor.process(uiObservation(body = "Message for case [$body] which is unique")).created()
            service(this).drain()
            assertEquals(body, DeliveryStatus.PENDING, store.events[event.eventId]!!.deliveryStatus)
            clock.advance(10 * 60_000)
        }
    }

    @Test
    fun `server errors and refused credentials stay retryable`() = runTest {
        for (status in listOf(500, 503, 408, 429, 401, 403)) {
            transport.respond = { TransportResponse(status, "") }
            val event = processor.process(uiObservation(body = "Message for status $status is distinct text")).created()
            service(this).drain()
            assertEquals("status $status", DeliveryStatus.PENDING, store.events[event.eventId]!!.deliveryStatus)
            clock.advance(10 * 60_000)
        }
    }

    @Test
    fun `a request the server calls invalid stops retrying and is reported`() = runTest {
        transport.respond = { TransportResponse(422, """{"error":"invalid_alert"}""") }
        val event = processor.process(providerObservation()).created()
        val summary = service(this).drain()

        assertEquals(1, summary.rejected)
        assertEquals(DeliveryStatus.REJECTED, store.events[event.eventId]!!.deliveryStatus)
        assertEquals("http_422", store.events[event.eventId]!!.lastDeliveryError)
    }

    @Test
    fun `a real alert is never abandoned by default`() = runTest {
        transport.respond = { throw IOException("down") }
        val event = processor.process(providerObservation()).created()
        val service = service(this)
        repeat(40) {
            service.drain()
            clock.advance(10 * 60_000)
        }
        val stored = store.events[event.eventId]!!
        assertEquals(DeliveryStatus.PENDING, stored.deliveryStatus)
        assertEquals(40, stored.deliveryAttempts)
    }

    @Test
    fun `an operator retry limit is honoured`() = runTest {
        config = config.copy(retryMaxAttempts = 2)
        transport.respond = { throw IOException("down") }
        val event = processor.process(providerObservation()).created()
        val service = service(this)
        repeat(3) {
            service.drain()
            clock.advance(10 * 60_000)
        }
        assertEquals(2, transport.requests.size)
        assertEquals(DeliveryStatus.REJECTED, store.events[event.eventId]!!.deliveryStatus)
    }

    @Test
    fun `an upgrade arriving mid request is not lost`() = runTest {
        val event = processor.process(uiObservation()).created()
        transport.respond = { request ->
            store.events[event.eventId] = store.events[event.eventId]!!.copy(revision = 2)
            FakeTransport.acknowledge(request)
        }
        service(this).drain()

        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.first().body.contains("\"revision\":1"))
        assertTrue(transport.requests.last().body.contains("\"revision\":2"))
        val stored = store.events[event.eventId]!!
        assertEquals(2, stored.revision)
        assertEquals(DeliveryStatus.DELIVERED, stored.deliveryStatus)
    }

    @Test
    fun `nothing is sent or lost until a server is configured`() = runTest {
        config = config.copy(serverHost = "")
        val event = processor.process(providerObservation()).created()
        service(this).drain()
        assertTrue(transport.requests.isEmpty())
        assertEquals(DeliveryStatus.PENDING, store.events[event.eventId]!!.deliveryStatus)
        assertEquals(0, store.events[event.eventId]!!.deliveryAttempts)
    }

    @Test
    fun `no credential means no authorization header`() = runTest {
        processor.process(providerObservation())
        AlertDeliveryService(
            store, transport, { config }, { null }, { _, _, _ -> }, RETAIL_BUILD, { }, this, clock.read,
        ).drain()
        assertNull(transport.requests.single().headers["Authorization"])
    }

    @Test
    fun `backoff doubles, stays within jitter and respects the cap`() {
        val backoff = BackoffPolicy(5_000, 300_000, Random(7))
        assertTrue(backoff.delayMs(1) in 4_000..6_000)
        assertTrue(backoff.delayMs(2) in 8_000..12_000)
        assertTrue(backoff.delayMs(3) in 16_000..24_000)
        assertTrue(backoff.delayMs(4) in 32_000..48_000)
        repeat(50) { assertTrue(backoff.delayMs(40) in 240_000..300_000) }
        assertTrue(backoff.delayMs(0) >= 1)
    }
}
