package app.cellalerthook.domain

import app.cellalerthook.ARABIC_ALERT
import app.cellalerthook.FakeClock
import app.cellalerthook.FakeStore
import app.cellalerthook.LAB_BUILD
import app.cellalerthook.cellBroadcastMetadata
import app.cellalerthook.created
import app.cellalerthook.hiddenObservation
import app.cellalerthook.newProcessor
import app.cellalerthook.providerObservation
import app.cellalerthook.uiObservation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertProcessorTest {
    private val store = FakeStore()
    private val clock = FakeClock()
    private val processor = newProcessor(store, clock)

    @Test
    fun `provider row on a retail build is verified at the Cell Broadcast stack only`() = runTest {
        val event = processor.process(providerObservation()).created()
        assertEquals(VerificationStatus.CELL_BROADCAST_STACK, event.verification)
        assertEquals(AlertSource.ANDROID_CELL_BROADCAST, event.source)
        assertEquals(Confidence.HIGH, event.confidence)
        assertEquals(AlertClass.NATIONAL_ALERT, event.alertClass)
        assertEquals("ar", event.language)
        assertTrue("origin_not_cryptographically_verifiable" in event.validationNotes)
    }

    @Test
    fun `provider row on a debuggable build stays marked as lab`() = runTest {
        val lab = newProcessor(store, clock, LAB_BUILD)
        val event = lab.process(providerObservation()).created()
        assertEquals(VerificationStatus.CELL_BROADCAST_STACK_LAB, event.verification)
        assertTrue("debuggable_build_lab_injection_possible" in event.validationNotes)
    }

    @Test
    fun `alarming text from UI is never classified or given a severity`() = runTest {
        val event = processor.process(uiObservation(body = "EXTREME MISSILE ATTACK TSUNAMI EVACUATE NOW")).created()
        assertEquals(AlertClass.UNKNOWN, event.alertClass)
        assertEquals(Severity.UNKNOWN, event.severity)
        assertEquals(VerificationStatus.SYSTEM_COMPONENT_UI, event.verification)
    }

    @Test
    fun `text from a user installed package is unverified however it looks`() = runTest {
        val event = processor.process(
            uiObservation(trust = OriginTrust.USER_INSTALLED, originPackage = "com.example.fake"),
        ).created()
        assertEquals(VerificationStatus.UNVERIFIED, event.verification)
        assertEquals(AlertSource.UNVERIFIED_APP_UI, event.source)
        assertEquals(Confidence.LOW, event.confidence)
    }

    @Test
    fun `notification is never labelled as Cell Broadcast`() = runTest {
        val event = processor.process(uiObservation(method = CaptureMethod.NOTIFICATION)).created()
        assertEquals(AlertSource.ANDROID_SYSTEM_NOTIFICATION, event.source)
        assertEquals(VerificationStatus.SYSTEM_COMPONENT_UI, event.verification)
    }

    @Test
    fun `a claimed provider method without metadata earns no verification`() = runTest {
        val forged = providerObservation().copy(cellBroadcast = null)
        val event = processor.process(forged).created()
        assertEquals(VerificationStatus.SYSTEM_COMPONENT_UI, event.verification)
    }

    @Test
    fun `severity comes only from the CMAS field`() = runTest {
        val event = processor.process(
            providerObservation(metadata = cellBroadcastMetadata(cmasSeverity = 0)),
        ).created()
        assertEquals(Severity.EXTREME, event.severity)
    }

    @Test
    fun `official test channels are flagged as drills`() = runTest {
        val exercise = processor.process(
            providerObservation(metadata = cellBroadcastMetadata(messageId = 4381)),
        ).created()
        assertEquals(AlertClass.EXERCISE, exercise.alertClass)
        assertEquals(true, exercise.alertClass.testOrExercise)
    }

    @Test
    fun `repeat of the same broadcast is suppressed but audited`() = runTest {
        val first = processor.process(providerObservation()).created()
        clock.advance(60_000)
        val second = processor.process(providerObservation())

        assertTrue(second is ProcessingResult.Duplicate)
        assertEquals(1, store.events.size)
        assertEquals(1, store.events[first.eventId]!!.duplicateCount)
        assertEquals(2, store.observations.size)
        assertEquals(ObservationOutcome.DUPLICATE, store.observations.last().outcome)
    }

    @Test
    fun `a new message reusing a serial is a new incident and is never suppressed`() = runTest {
        val first = processor.process(providerObservation()).created()
        clock.advance(60 * 60_000L)
        val reused = providerObservation(body = "رسالة مختلفة تماما عن تحذير جديد صدر الآن من الدفاع المدني")

        val result = processor.process(reused)

        assertTrue(result is ProcessingResult.Created)
        assertEquals(first.dedupKey, (result as ProcessingResult.Created).event.dedupKey)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `a reused serial still upgrades the alert it really belongs to`() = runTest {
        processor.process(providerObservation())
        clock.advance(60 * 60_000L)
        val newText = "تحذير جديد مختلف صدر الآن من الدفاع المدني ويتطلب المتابعة"
        val ui = processor.process(uiObservation(body = newText)).created()

        val result = processor.process(providerObservation(body = newText))

        assertTrue(result is ProcessingResult.Upgraded)
        assertEquals(ui.eventId, (result as ProcessingResult.Upgraded).event.eventId)
    }

    @Test
    fun `same text by another method corroborates instead of creating an incident`() = runTest {
        val first = processor.process(providerObservation()).created()
        val second = processor.process(uiObservation())

        assertTrue(second is ProcessingResult.Duplicate)
        val stored = store.events[first.eventId]!!
        assertEquals(1, store.events.size)
        assertEquals(setOf(CaptureMethod.ACCESSIBILITY), stored.corroboratingMethods)
        assertEquals("National alert", stored.title)
        assertEquals(1, stored.revision)
    }

    @Test
    fun `UI text then provider row upgrades one event and resends it`() = runTest {
        val first = processor.process(uiObservation()).created()
        store.events[first.eventId] = first.copy(deliveryStatus = DeliveryStatus.DELIVERED)

        val result = processor.process(providerObservation())

        assertTrue(result is ProcessingResult.Upgraded)
        val upgraded = store.events[first.eventId]!!
        assertEquals(1, store.events.size)
        assertEquals(2, upgraded.revision)
        assertEquals(VerificationStatus.CELL_BROADCAST_STACK, upgraded.verification)
        assertEquals(DeliveryStatus.PENDING, upgraded.deliveryStatus)
        assertEquals("National alert", upgraded.title)
        assertEquals(AlertClass.NATIONAL_ALERT, upgraded.alertClass)
        assertTrue(CaptureMethod.ACCESSIBILITY in upgraded.corroboratingMethods)
    }

    @Test
    fun `a weaker observation never downgrades a verified event`() = runTest {
        val first = processor.process(providerObservation()).created()
        processor.process(uiObservation(trust = OriginTrust.USER_INSTALLED))
        assertEquals(VerificationStatus.CELL_BROADCAST_STACK, store.events[first.eventId]!!.verification)
    }

    @Test
    fun `a notification that shortens the message still matches it`() = runTest {
        processor.process(providerObservation())
        val shortened = ARABIC_ALERT.substringBefore('.')
        val result = processor.process(uiObservation(body = shortened, method = CaptureMethod.NOTIFICATION))
        assertTrue(result is ProcessingResult.Duplicate)
    }

    @Test
    fun `a simulation never merges with a real alert carrying the same text`() = runTest {
        processor.process(providerObservation())
        val simulated = processor.process(uiObservation(simulated = true))
        assertTrue(simulated is ProcessingResult.Created)
        assertEquals(2, store.events.size)
        assertEquals(1, store.events.values.count { it.simulated })
    }

    @Test
    fun `a real alert is never absorbed by an earlier simulation`() = runTest {
        processor.process(uiObservation(simulated = true))
        assertTrue(processor.process(providerObservation()) is ProcessingResult.Created)
    }

    @Test
    fun `simulation is marked as such whatever the capture method`() = runTest {
        val event = processor.process(uiObservation(simulated = true, trust = OriginTrust.SELF)).created()
        assertEquals(VerificationStatus.SIMULATED, event.verification)
        assertEquals(AlertSource.SIMULATION, event.source)
        assertTrue(event.simulated)
    }

    @Test
    fun `a new broadcast with the same text after the window is a new incident`() = runTest {
        processor.process(providerObservation())
        clock.advance(31 * 60_000L)
        val reissued = providerObservation(metadata = cellBroadcastMetadata(serial = 0xC2B0))
        assertTrue(processor.process(reissued) is ProcessingResult.Created)
    }

    @Test
    fun `an undismissed dialog read again hours later is not a new incident`() = runTest {
        val first = processor.process(providerObservation()).created()
        clock.advance(6 * 3_600_000L)
        val reread = processor.process(uiObservation())

        assertTrue(reread is ProcessingResult.Duplicate)
        assertEquals(1, store.events.size)
        assertEquals(first.eventId, (reread as ProcessingResult.Duplicate).event.eventId)
    }

    @Test
    fun `UI text alone is not resent while it stays on screen`() = runTest {
        processor.process(uiObservation())
        clock.advance(6 * 3_600_000L)
        assertTrue(processor.process(uiObservation()) is ProcessingResult.Duplicate)
    }

    @Test
    fun `UI text is treated as new again after a full day`() = runTest {
        processor.process(uiObservation())
        clock.advance(25 * 3_600_000L)
        assertTrue(processor.process(uiObservation()) is ProcessingResult.Created)
    }

    @Test
    fun `same text and message code inside the window is a repeat, whatever the update number`() = runTest {
        processor.process(providerObservation())
        clock.advance(5 * 60_000L)
        val repeat = providerObservation(metadata = cellBroadcastMetadata(serial = 0xC291))
        assertTrue(processor.process(repeat) is ProcessingResult.Duplicate)
    }

    @Test
    fun `a new message code is a new alert even with identical words`() = runTest {
        // Measured on 18 March 2026: warning, all-clear, then the same warning again.
        processor.process(providerObservation())
        clock.advance(5 * 60_000L)
        val again = providerObservation(metadata = cellBroadcastMetadata(serial = 0xC2A0))
        assertTrue(processor.process(again) is ProcessingResult.Created)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `dialog text is still upgraded by the provider row it came from`() = runTest {
        val event = processor.process(uiObservation()).created()
        val upgraded = processor.process(providerObservation()) as ProcessingResult.Upgraded
        assertEquals(event.eventId, upgraded.event.eventId)
    }

    @Test
    fun `Arabic and English versions are separate messages`() = runTest {
        processor.process(providerObservation())
        val english = providerObservation(
            body = "Emergency alert from Civil Defense. Please follow official instructions.",
            metadata = cellBroadcastMetadata(messageId = 4383),
        ).copy(languageHint = "en")
        assertTrue(processor.process(english) is ProcessingResult.Created)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `hidden text is reported as hidden, and the placeholder is never passed off as the alert`() = runTest {
        val event = processor.process(hiddenObservation()).created()
        assertEquals(HiddenAlertText.MESSAGE, event.message)
        assertEquals(LanguageDetector.UNKNOWN, event.language)
        assertTrue(HiddenAlertText.NOTE in event.validationNotes)
        assertEquals(AlertSource.ANDROID_SYSTEM_NOTIFICATION, event.source)
        assertEquals(VerificationStatus.SYSTEM_COMPONENT_UI, event.verification)
    }

    @Test
    fun `two different hidden alerts stay two incidents although their text is identical`() = runTest {
        processor.process(hiddenObservation(postTimeMs = 1)).created()
        assertTrue(processor.process(hiddenObservation(postTimeMs = 2)) is ProcessingResult.Created)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `the same hidden notification read again is not a new incident`() = runTest {
        processor.process(hiddenObservation(postTimeMs = 1)).created()
        clock.advance(6 * 3_600_000L)
        val again = hiddenObservation(postTimeMs = 1, observedAtMs = clock.nowMs)
        assertTrue(processor.process(again) is ProcessingResult.Duplicate)
        assertEquals(1, store.events.size)
    }

    @Test
    fun `a hidden notification suppressed once stays suppressed when read again at startup`() = runTest {
        processor.process(providerObservation()).created()
        assertTrue(processor.process(hiddenObservation(postTimeMs = 7)) is ProcessingResult.Duplicate)
        // After a restart the detectors have not reported yet, and the notification is still posted.
        val again = processor.process(hiddenObservation(postTimeMs = 7, textDetectorActive = false))
        assertTrue(again is ProcessingResult.Duplicate)
        assertEquals(1, store.events.size)
    }

    @Test
    fun `an old event does not cover a hidden alert once the text detectors have stopped`() = runTest {
        processor.process(providerObservation()).created()
        assertTrue(processor.process(hiddenObservation(textDetectorActive = false)) is ProcessingResult.Created)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `a hidden alert with a working detector but nothing reported is a new incident`() = runTest {
        assertTrue(processor.process(hiddenObservation(textDetectorActive = true)) is ProcessingResult.Created)
    }

    @Test
    fun `leaving the dialog of an alert already read adds no incident and changes nothing`() = runTest {
        val event = processor.process(providerObservation()).created()
        clock.advance(8 * 3_600_000L)
        val result = processor.process(hiddenObservation()) as ProcessingResult.Duplicate
        assertEquals(1, store.events.size)
        assertEquals(event.message, result.event.message)
        assertEquals(event.revision, result.event.revision)
        assertNull(result.event.title)
        assertTrue(result.event.corroboratingMethods.isEmpty())
    }

    @Test
    fun `a hidden notification never replaces text from a weaker source`() = runTest {
        val event = processor.process(uiObservation(trust = OriginTrust.USER_INSTALLED)).created()
        assertTrue(processor.process(hiddenObservation()) is ProcessingResult.Duplicate)
        assertEquals(event.message, store.events[event.eventId]!!.message)
    }

    @Test
    fun `an earlier readable notification does not absorb a hidden alert`() = runTest {
        processor.process(uiObservation(method = CaptureMethod.NOTIFICATION)).created()
        assertTrue(processor.process(hiddenObservation()) is ProcessingResult.Created)
    }

    @Test
    fun `a hidden alert a day after anything else is a new incident`() = runTest {
        processor.process(providerObservation()).created()
        clock.advance(24 * 3_600_000L + 1)
        assertTrue(processor.process(hiddenObservation()) is ProcessingResult.Created)
    }

    @Test
    fun `a hidden event does not absorb the next readable alert`() = runTest {
        processor.process(hiddenObservation()).created()
        assertTrue(processor.process(providerObservation()) is ProcessingResult.Created)
    }

    @Test
    fun `a hidden simulation never merges with a real alert`() = runTest {
        processor.process(providerObservation()).created()
        assertTrue(processor.process(hiddenObservation(simulated = true)) is ProcessingResult.Created)
    }

    @Test
    fun `blank and missing bodies are rejected and audited`() = runTest {
        assertTrue(processor.process(uiObservation(body = null)) is ProcessingResult.Rejected)
        assertTrue(processor.process(uiObservation(body = " \n\t ")) is ProcessingResult.Rejected)
        assertTrue(store.events.isEmpty())
        assertEquals(2, store.observations.size)
        assertEquals(ObservationOutcome.REJECTED, store.observations.first().outcome)
    }

    @Test
    fun `original text is stored exactly apart from control characters`() = runTest {
        val event = processor.process(uiObservation(body = "\u0000 $ARABIC_ALERT\r\n")).created()
        assertEquals(ARABIC_ALERT, event.message)
    }

    @Test
    fun `oversized input is bounded`() = runTest {
        val event = processor.process(uiObservation(body = "x".repeat(50_000))).created()
        assertEquals(TextSanitizer.MAX_BODY_LENGTH, event.message.length)
    }

    @Test
    fun `new event starts pending and carries no delivery history`() = runTest {
        val event = processor.process(providerObservation(catchUp = true)).created()
        assertEquals(DeliveryStatus.PENDING, event.deliveryStatus)
        assertEquals(0, event.deliveryAttempts)
        assertNull(event.deliveredAtMs)
        assertTrue(event.catchUp)
        assertFalse(event.simulated)
    }
}
