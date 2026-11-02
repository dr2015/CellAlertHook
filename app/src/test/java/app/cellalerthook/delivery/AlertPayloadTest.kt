package app.cellalerthook.delivery

import app.cellalerthook.ARABIC_ALERT
import app.cellalerthook.FakeClock
import app.cellalerthook.FakeStore
import app.cellalerthook.LAB_BUILD
import app.cellalerthook.created
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.HiddenAlertText
import app.cellalerthook.hiddenObservation
import app.cellalerthook.newProcessor
import app.cellalerthook.providerObservation
import app.cellalerthook.uiObservation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPayloadTest {
    private val store = FakeStore()
    private val clock = FakeClock(1_793_534_400_000L)
    private val processor = newProcessor(store, clock)

    @Test
    fun `payload has every field of the agreed contract`() = runTest {
        val event = processor.process(providerObservation()).created()
        val json = Json.parseToJsonElement(AlertPayload.from(event).toJson()).jsonObject

        listOf(
            "eventId", "deviceId", "timestamp", "source", "captureMethod", "originPackage",
            "title", "message", "language", "severity", "verified", "simulation",
        ).forEach { assertTrue("missing $it", it in json) }
        assertEquals("android_cell_broadcast", json["source"]!!.jsonPrimitive.content)
        assertEquals("cell_broadcast_provider", json["captureMethod"]!!.jsonPrimitive.content)
        assertEquals("cellalerthook-test", json["deviceId"]!!.jsonPrimitive.content)
        assertEquals("unknown", json["severity"]!!.jsonPrimitive.content)
        assertEquals("ar", json["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun `textHidden tells a server the message is a note, not the alert`() = runTest {
        val readable = AlertPayload.from(processor.process(providerObservation()).created())
        val hidden = AlertPayload.from(processor.process(hiddenObservation(simulated = true)).created())
        assertFalse(readable.textHidden)
        assertTrue(hidden.textHidden)
        assertFalse(hidden.verified)
        assertTrue(hidden.verification.notes.contains(HiddenAlertText.NOTE))
    }

    @Test
    fun `timestamp is ISO 8601 UTC`() = runTest {
        val event = processor.process(providerObservation()).created()
        assertEquals("2026-11-01T12:00:00Z", AlertPayload.from(event).timestamp)
    }

    @Test
    fun `Arabic survives serialization unescaped`() = runTest {
        val event = processor.process(providerObservation()).created()
        assertTrue(AlertPayload.from(event).toJson().contains(ARABIC_ALERT))
    }

    @Test
    fun `absent values are explicit nulls, so the shape never changes`() = runTest {
        val event = processor.process(uiObservation(title = null)).created()
        val json = Json.parseToJsonElement(AlertPayload.from(event).toJson()).jsonObject
        assertEquals(JsonNull, json["title"])
        assertEquals(JsonNull, json["cellBroadcast"])
    }

    @Test
    fun `verified is true only for the retail Cell Broadcast stack`() = runTest {
        assertTrue(AlertPayload.from(processor.process(providerObservation()).created()).verified)

        val lab = newProcessor(FakeStore(), clock, LAB_BUILD)
        assertFalse(AlertPayload.from(lab.process(providerObservation()).created()).verified)

        val ui = newProcessor(FakeStore(), clock)
        assertFalse(AlertPayload.from(ui.process(uiObservation()).created()).verified)

        val notification = newProcessor(FakeStore(), clock)
        val payload = AlertPayload.from(notification.process(uiObservation(method = CaptureMethod.NOTIFICATION)).created())
        assertFalse(payload.verified)
        assertEquals("android_system_notification", payload.source)
    }

    @Test
    fun `a simulation is marked and can never be verified`() = runTest {
        val payload = AlertPayload.from(processor.process(uiObservation(simulated = true)).created())
        assertTrue(payload.simulation)
        assertFalse(payload.verified)
        assertEquals("simulation", payload.source)
        assertEquals("simulated", payload.verification.status)
    }

    @Test
    fun `structured Cell Broadcast fields reach the server`() = runTest {
        val event = processor.process(providerObservation()).created()
        val json = Json.parseToJsonElement(AlertPayload.from(event).toJson()).jsonObject
        val cellBroadcast = json["cellBroadcast"]!!.jsonObject
        assertEquals(4370, cellBroadcast["messageId"]!!.jsonPrimitive.int)
        assertEquals("42001", cellBroadcast["plmn"]!!.jsonPrimitive.content)
        assertEquals("national_alert", json["alertClass"]!!.jsonPrimitive.content)
        assertFalse(json["testOrExercise"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an unknown drill status stays null rather than false`() = runTest {
        val event = processor.process(uiObservation()).created()
        assertEquals(null, AlertPayload.from(event).testOrExercise)
    }

    @Test
    fun `acknowledgment parsing ignores fields added later`() {
        val json = Json { ignoreUnknownKeys = true }
        val ack = json.decodeFromString<DeliveryAck>("""{"ack":true,"eventId":"e","revision":1,"future":"x"}""")
        assertTrue(ack.ack)
        assertEquals("e", ack.eventId)
    }
}
