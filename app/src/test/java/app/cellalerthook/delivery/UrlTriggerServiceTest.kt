package app.cellalerthook.delivery

import app.cellalerthook.FakeClock
import app.cellalerthook.FakeStore
import app.cellalerthook.FakeTriggerStore
import app.cellalerthook.LAB_BUILD
import app.cellalerthook.RETAIL_BUILD
import app.cellalerthook.cellBroadcastMetadata
import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.TriggerStatus
import app.cellalerthook.domain.AlertProcessor
import app.cellalerthook.domain.BuildProvenance
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.ProcessingResult
import app.cellalerthook.domain.RawAlertObservation
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.TriggerMethod
import app.cellalerthook.newProcessor
import app.cellalerthook.providerObservation
import app.cellalerthook.uiObservation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

private const val ON = "http://192.168.1.50/hook/on"
private const val OFF = "http://192.168.1.50/hook/off"
private const val WARNING = "تحذير\nتتعرض المنطقة لتهديد جوي معادي\nWarning\nThe area is under a hostile aerial threat."
private const val ALL_CLEAR = "تنبيه\nزال الخطر\nAlert\nThe threat has passed, Stay away from the impact site."
private val TEST_ZONE = ZoneOffset.ofHours(3)

/** Serials are 0xC000 plus a message code shifted by four, so each [code] is a distinct real issue. */
private fun serial(code: Int) = 0xC000 or (code shl 4)

@OptIn(ExperimentalCoroutinesApi::class)
class UrlTriggerServiceTest {
    private val alerts = FakeStore()
    private val store = FakeTriggerStore()
    private val clock = FakeClock()
    private val calls = mutableListOf<String>()
    private var respond: (String) -> Int = { 200 }

    private var config = ReceiverConfig(
        triggerArmed = true,
        scheduleEnabled = false,
        scheduleDays = setOf(7, 1, 2, 3, 4),
        scheduleStartMinute = 7 * 60,
        scheduleEndMinute = 15 * 60,
        retryBaseSeconds = 5,
        retryMaxSeconds = 300,
    )
    private var urls = mapOf(TriggerAction.ACTIVATE to ON, TriggerAction.CLEAR to OFF)

    private fun processor(provenance: BuildProvenance = RETAIL_BUILD): AlertProcessor = newProcessor(alerts, clock, provenance)

    private fun TestScope.service() = UrlTriggerService(
        store = store,
        transport = { _, url ->
            calls += url
            respond(url)
        },
        config = { config },
        urlFor = { urls[it] },
        log = { _, _, _ -> },
        scheduler = { },
        scope = this,
        clock = clock.read,
        random = Random(1),
        zone = { TEST_ZONE },
    )

    /** Its clock follows the test's virtual time, so a wait for the hours to start really passes. */
    private fun TestScope.serviceOnTestTime(): UrlTriggerService {
        val start = clock.nowMs
        return UrlTriggerService(
            store = store,
            transport = { _, url ->
                calls += url
                respond(url)
            },
            config = { config },
            urlFor = { urls[it] },
            log = { _, _, _ -> },
            scheduler = { },
            scope = this,
            clock = { start + testScheduler.currentTime },
            random = Random(1),
            zone = { TEST_ZONE },
        )
    }

    /** 2 November 2026 is a Monday, and the test week is Sunday to Thursday, 07:00 to 15:00. */
    private fun at(day: Int, hour: Int, minute: Int = 0) {
        clock.nowMs = LocalDateTime.of(2026, 11, day, hour, minute).atZone(TEST_ZONE).toInstant().toEpochMilli()
    }

    private fun alert(text: String, code: Int, receivedAtMs: Long = clock.nowMs, messageId: Int = 4377) = providerObservation(
        body = text,
        metadata = cellBroadcastMetadata(messageId = messageId, serial = serial(code), receivedTimeMs = receivedAtMs),
    )

    private suspend fun AlertProcessor.event(observation: RawAlertObservation): AlertEvent = when (val result = process(observation)) {
        is ProcessingResult.Created -> result.event
        is ProcessingResult.Upgraded -> result.event
        else -> error("Expected a new or upgraded event, got $result")
    }

    private suspend fun TestScope.fire(service: UrlTriggerService, event: AlertEvent) {
        service.onEvent(event)
        advanceUntilIdle()
    }

    private fun statusOf(event: AlertEvent) = store.commands.getValue(event.eventId).status

    @Test
    fun `a warning calls the on URL at once`() = runTest {
        val event = processor().event(alert(WARNING, 1))
        fire(service(), event)
        assertEquals(listOf(ON), calls)
        assertEquals(TriggerStatus.SENT, statusOf(event))
    }

    @Test
    fun `an all-clear calls the off URL`() = runTest {
        fire(service(), processor().event(alert(ALL_CLEAR, 2)))
        assertEquals(listOf(OFF), calls)
    }

    @Test
    fun `POST is used when configured`() = runTest {
        config = config.copy(triggerMethod = TriggerMethod.POST)
        var used: TriggerMethod? = null
        val service = UrlTriggerService(
            store, { method, _ -> used = method; 200 }, { config }, { urls[it] }, { _, _, _ -> }, { }, this, clock.read,
        )
        fire(service, processor().event(alert(WARNING, 1)))
        assertEquals(TriggerMethod.POST, used)
    }

    @Test
    fun `a warning repeated while the target is on does not call again`() = runTest {
        val service = service()
        val processor = processor()
        fire(service, processor.event(alert(WARNING, 1)))
        clock.advance(60_000)
        val repeat = processor.event(alert(WARNING, 2))
        fire(service, repeat)
        assertEquals(listOf(ON), calls)
        assertEquals(TriggerStatus.REPEAT, statusOf(repeat))
    }

    @Test
    fun `a warning after an all-clear fires again, within minutes and with identical words`() = runTest {
        val service = service()
        val processor = processor()
        fire(service, processor.event(alert(WARNING, 1)))
        clock.advance(60_000)
        fire(service, processor.event(alert(ALL_CLEAR, 2)))
        clock.advance(5 * 60_000L)
        fire(service, processor.event(alert(WARNING, 3)))
        assertEquals(listOf(ON, OFF, ON), calls)
    }

    @Test
    fun `an alert no rule matches does nothing`() = runTest {
        fire(service(), processor().event(alert("Roads to the Trade Centre are congested. Please use other routes.", 1, messageId = 4396)))
        assertTrue(calls.isEmpty())
        assertTrue(store.commands.isEmpty())
    }

    @Test
    fun `nothing fires until armed`() = runTest {
        config = config.copy(triggerArmed = false)
        fire(service(), processor().event(alert(WARNING, 1)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `official test and exercise channels never fire, even with the real words`() = runTest {
        val service = service()
        val processor = processor()
        listOf(4380, 4381, 4382, 4355).forEachIndexed { index, channel ->
            fire(service, processor.event(alert(WARNING, 10 + index, messageId = channel)))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `text read from the screen never fires, whatever it says`() = runTest {
        fire(service(), processor().event(uiObservation(body = WARNING)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a notification never fires, whatever it says`() = runTest {
        fire(service(), processor().event(uiObservation(body = WARNING, method = CaptureMethod.NOTIFICATION)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a simulation never fires, whatever it says`() = runTest {
        fire(service(), processor().event(uiObservation(body = WARNING, simulated = true)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `an injected alert on a test image fires only when the lab flag is set`() = runTest {
        val processor = processor(LAB_BUILD)
        fire(service(), processor.event(alert(WARNING, 1)))
        assertTrue(calls.isEmpty())
        config = config.copy(allowLabTriggers = true)
        fire(service(), processor.event(alert(ALL_CLEAR, 2)))
        assertEquals(listOf(OFF), calls)
    }

    @Test
    fun `screen text fires once the provider row confirms it`() = runTest {
        val service = service()
        val processor = processor()
        fire(service, processor.event(uiObservation(body = WARNING)))
        fire(service, processor.event(alert(WARNING, 1)))
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `a failed call is retried until it gets through`() = runTest {
        val service = service()
        var failures = 3
        respond = { if (failures-- > 0) 503 else 200 }
        val event = processor().event(alert(WARNING, 1))
        fire(service, event)
        repeat(5) {
            clock.advance(301_000)
            service.send()
        }
        assertEquals(4, calls.size)
        assertEquals(TriggerStatus.SENT, statusOf(event))
    }

    @Test
    fun `a network failure is retried, not lost`() = runTest {
        val service = service()
        var down = true
        respond = { if (down) throw IOException("no route") else 200 }
        val event = processor().event(alert(WARNING, 1))
        fire(service, event)
        assertEquals(TriggerStatus.PENDING, statusOf(event))
        down = false
        clock.advance(301_000)
        service.send()
        assertEquals(TriggerStatus.SENT, statusOf(event))
    }

    @Test
    fun `a trigger stuck behind a failure can never reach the target after the all-clear`() = runTest {
        val service = service()
        val processor = processor()
        respond = { if (it == ON) 503 else 200 }
        val warning = processor.event(alert(WARNING, 1))
        fire(service, warning)
        clock.advance(60_000)
        fire(service, processor.event(alert(ALL_CLEAR, 2)))
        respond = { 200 }
        repeat(5) {
            clock.advance(301_000)
            service.send()
        }
        assertEquals(listOf(ON, OFF), calls)
        assertEquals(TriggerStatus.SUPERSEDED, statusOf(warning))
    }

    @Test
    fun `an older warning found after a newer all-clear changes nothing`() = runTest {
        val service = service()
        val processor = processor()
        val warningAt = clock.nowMs
        clock.advance(600_000)
        fire(service, processor.event(alert(ALL_CLEAR, 2)))
        val late = processor.event(alert(WARNING, 1, receivedAtMs = warningAt))
        fire(service, late)
        assertEquals(listOf(OFF), calls)
        assertEquals(TriggerStatus.SUPERSEDED, statusOf(late))
    }

    @Test
    fun `a warning during active hours fires`() = runTest {
        config = config.copy(scheduleEnabled = true)
        at(2, 10)
        fire(service(), processor().event(alert(WARNING, 1)))
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `active hours include the start and exclude the end`() = runTest {
        config = config.copy(scheduleEnabled = true)
        val service = service()
        val processor = processor()
        at(2, 7)
        fire(service, processor.event(alert(WARNING, 1)))
        at(2, 7, 1)
        fire(service, processor.event(alert(ALL_CLEAR, 2)))
        at(2, 15)
        val late = processor.event(alert(WARNING, 3))
        fire(service, late)
        assertEquals(listOf(ON, OFF), calls)
        assertEquals(TriggerStatus.OUTSIDE_HOURS, statusOf(late))
    }

    @Test
    fun `a warning after hours does not fire`() = runTest {
        config = config.copy(scheduleEnabled = true)
        at(2, 20)
        val event = processor().event(alert(WARNING, 1))
        fire(service(), event)
        assertTrue(calls.isEmpty())
        assertEquals(TriggerStatus.OUTSIDE_HOURS, statusOf(event))
    }

    @Test
    fun `a warning on the weekend does not fire`() = runTest {
        config = config.copy(scheduleEnabled = true)
        at(6, 10)
        fire(service(), processor().event(alert(WARNING, 1)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `an all-clear after hours still calls the off URL`() = runTest {
        config = config.copy(scheduleEnabled = true)
        val service = service()
        val processor = processor()
        at(2, 14, 50)
        fire(service, processor.event(alert(WARNING, 1)))
        at(2, 15, 10)
        fire(service, processor.event(alert(ALL_CLEAR, 2)))
        assertEquals(listOf(ON, OFF), calls)
    }

    @Test
    fun `a threat still active when the hours start fires at the opening`() = runTest {
        config = config.copy(scheduleEnabled = true)
        at(2, 6, 50)
        val service = serviceOnTestTime()
        service.onEvent(processor().event(alert(WARNING, 1)))
        runCurrent()
        advanceTimeBy(9 * 60_000L)
        runCurrent()
        assertTrue("silent before the hours start", calls.isEmpty())
        advanceTimeBy(61_000L)
        runCurrent()
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `an all-clear before the hours start cancels the waiting warning`() = runTest {
        config = config.copy(scheduleEnabled = true)
        at(2, 6, 50)
        val service = serviceOnTestTime()
        val processor = processor()
        val warning = processor.event(alert(WARNING, 1))
        service.onEvent(warning)
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        at(2, 6, 55)
        service.onEvent(processor.event(alert(ALL_CLEAR, 2)))
        advanceUntilIdle()
        assertEquals(listOf(OFF), calls)
        assertEquals(TriggerStatus.SUPERSEDED, statusOf(warning))
    }

    @Test
    fun `a warning hours before the hours start does not fire at the opening`() = runTest {
        config = config.copy(scheduleEnabled = true)
        val service = service()
        at(2, 5)
        val event = processor().event(alert(WARNING, 1))
        fire(service, event)
        at(2, 7)
        service.send()
        assertTrue(calls.isEmpty())
        assertEquals(TriggerStatus.OUTSIDE_HOURS, statusOf(event))
    }

    @Test
    fun `a warning that waited does not stop the next one`() = runTest {
        config = config.copy(scheduleEnabled = true)
        val service = service()
        val processor = processor()
        at(2, 20)
        fire(service, processor.event(alert(WARNING, 1)))
        at(3, 10)
        fire(service, processor.event(alert(WARNING, 2)))
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `the operator's button works outside active hours`() = runTest {
        config = config.copy(scheduleEnabled = true)
        val service = service()
        at(2, 20)
        service.manual(TriggerAction.ACTIVATE)
        advanceUntilIdle()
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `with the schedule off, a warning fires at any hour`() = runTest {
        at(6, 23)
        fire(service(), processor().event(alert(WARNING, 1)))
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `a warning and its all-clear read together never blip the target`() = runTest {
        val service = service()
        val processor = processor()
        val warningAt = clock.nowMs
        clock.advance(7_000)
        service.holding {
            service.onEvent(processor.event(alert(WARNING, 1, receivedAtMs = warningAt)))
            advanceUntilIdle()
            service.onEvent(processor.event(alert(ALL_CLEAR, 2)))
            advanceUntilIdle()
        }
        advanceUntilIdle()
        assertEquals(listOf(OFF), calls)
    }

    @Test
    fun `a warning read in a batch still fires when the batch ends`() = runTest {
        val service = service()
        val processor = processor()
        service.holding {
            service.onEvent(processor.event(alert(WARNING, 1)))
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
        }
        advanceUntilIdle()
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `a warning too old to matter does not fire`() = runTest {
        val receivedAt = clock.nowMs
        clock.advance(31 * 60_000L)
        val event = processor().event(alert(WARNING, 1, receivedAtMs = receivedAt))
        fire(service(), event)
        assertTrue(calls.isEmpty())
        assertEquals(TriggerStatus.EXPIRED, statusOf(event))
    }

    @Test
    fun `a warning that stays unsent past the limit expires instead of firing late`() = runTest {
        val service = service()
        respond = { 503 }
        val event = processor().event(alert(WARNING, 1))
        fire(service, event)
        respond = { 200 }
        calls.clear()
        clock.advance(31 * 60_000L)
        service.send()
        assertTrue(calls.isEmpty())
        assertEquals(TriggerStatus.EXPIRED, statusOf(event))
    }

    @Test
    fun `an all-clear is never too old to send`() = runTest {
        val receivedAt = clock.nowMs
        clock.advance(10 * 3_600_000L)
        fire(service(), processor().event(alert(ALL_CLEAR, 2, receivedAtMs = receivedAt)))
        assertEquals(listOf(OFF), calls)
    }

    @Test
    fun `a warning after an expired one still fires`() = runTest {
        val service = service()
        val processor = processor()
        val receivedAt = clock.nowMs
        clock.advance(31 * 60_000L)
        fire(service, processor.event(alert(WARNING, 1, receivedAtMs = receivedAt)))
        fire(service, processor.event(alert(WARNING, 2)))
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `a missing URL keeps the command waiting instead of dropping it`() = runTest {
        val service = service()
        urls = emptyMap()
        val event = processor().event(alert(WARNING, 1))
        fire(service, event)
        assertEquals(TriggerStatus.PENDING, statusOf(event))
        urls = mapOf(TriggerAction.ACTIVATE to ON)
        clock.advance(301_000)
        service.send()
        assertEquals(listOf(ON), calls)
    }

    @Test
    fun `a redirect from the controller counts as reached`() = runTest {
        respond = { 302 }
        val event = processor().event(alert(WARNING, 1))
        fire(service(), event)
        assertEquals(TriggerStatus.SENT, statusOf(event))
    }

    @Test
    fun `the operator can call the off URL while disarmed, and press it twice`() = runTest {
        config = config.copy(triggerArmed = false)
        val service = service()
        service.manual(TriggerAction.CLEAR)
        advanceUntilIdle()
        clock.advance(1000)
        service.manual(TriggerAction.CLEAR)
        advanceUntilIdle()
        assertEquals(listOf(OFF, OFF), calls)
    }

    @Test
    fun `a manual off beats a trigger still being retried`() = runTest {
        val service = service()
        respond = { if (it == ON) 503 else 200 }
        fire(service, processor().event(alert(WARNING, 1)))
        clock.advance(1000)
        service.manual(TriggerAction.CLEAR)
        advanceUntilIdle()
        clock.advance(301_000)
        service.send()
        assertEquals(listOf(ON, OFF), calls)
    }

    /** Warnings repeated word for word, with all-clears on the same channel, must alternate exactly. */
    @Test
    fun `a long run of warnings and all-clears alternates exactly`() = runTest {
        val service = service()
        val processor = processor()
        val start = clock.nowMs
        val texts = List(36) { index -> if (index % 2 == 0) WARNING else ALL_CLEAR }
        texts.forEachIndexed { index, text ->
            val receivedAt = start + index * 3_600_000L
            clock.nowMs = receivedAt + 500
            val observation = providerObservation(
                body = text,
                metadata = cellBroadcastMetadata(messageId = 4370, serial = (index + 1) shl 4, receivedTimeMs = receivedAt),
            )
            val result = processor.process(observation)
            assertTrue("every issue is its own event", result is ProcessingResult.Created)
            fire(service, (result as ProcessingResult.Created).event)
        }
        assertEquals(36, calls.size)
        calls.forEachIndexed { index, url -> assertEquals("call $index", if (index % 2 == 0) ON else OFF, url) }
    }
}
