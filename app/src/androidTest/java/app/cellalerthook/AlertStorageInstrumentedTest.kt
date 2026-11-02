package app.cellalerthook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cellalerthook.config.SecureCredentialStore
import app.cellalerthook.data.AlertRepository
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.AlertObservation
import app.cellalerthook.data.db.AppDatabase
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.domain.AlertClass
import app.cellalerthook.domain.AlertSource
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.CellBroadcastMetadata
import app.cellalerthook.domain.Confidence
import app.cellalerthook.domain.DeliveryStatus
import app.cellalerthook.domain.ObservationOutcome
import app.cellalerthook.domain.Severity
import app.cellalerthook.domain.VerificationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: real SQLite, real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class AlertStorageInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: AlertRepository

    private val metadata = CellBroadcastMetadata(
        rowId = 7, messageId = 4370, serialNumber = 49808, plmn = "42001", lac = 1, cid = 2, slotIndex = 0,
        subscriptionId = 1, receivedTimeMs = 1000, messageFormat = 1, messagePriority = 3, dataCodingScheme = 17,
        cmasMessageClass = 0, cmasCategory = -1, cmasResponseType = -1, cmasSeverity = -1, cmasUrgency = -1,
        cmasCertainty = -1, etwsWarningType = null, displayed = true,
    )

    private fun event(id: String, simulated: Boolean = false, cellBroadcast: CellBroadcastMetadata? = metadata) = AlertEvent(
        eventId = id,
        deviceId = "device",
        detectedAtMs = 5_000,
        receivedAtMs = 4_000,
        title = null,
        message = "تنبيه طوارئ",
        language = "ar",
        captureMethod = CaptureMethod.CELL_BROADCAST_PROVIDER,
        source = AlertSource.ANDROID_CELL_BROADCAST,
        originPackage = "pkg",
        originClass = "cls",
        alertClass = AlertClass.NATIONAL_ALERT,
        severity = Severity.UNKNOWN,
        verification = VerificationStatus.CELL_BROADCAST_STACK,
        confidence = Confidence.HIGH,
        validationNotes = listOf("a", "b"),
        simulated = simulated,
        catchUp = false,
        dedupKey = cellBroadcast?.dedupKey,
        fingerprint = "hash",
        revision = 1,
        duplicateCount = 0,
        lastObservedAtMs = 5_000,
        corroboratingMethods = setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.NOTIFICATION),
        cellBroadcast = cellBroadcast,
        deliveryStatus = DeliveryStatus.PENDING,
        deliveryAttempts = 0,
        nextAttemptAtMs = 5_000,
        deliveredAtMs = null,
        lastDeliveryError = null,
        lastHttpStatus = null,
    )

    private fun observation(eventId: String) = AlertObservation(
        eventId = eventId,
        observedAtMs = 5_000,
        method = CaptureMethod.CELL_BROADCAST_PROVIDER,
        originPackage = "pkg",
        originClass = "cls",
        outcome = ObservationOutcome.CREATED,
        detail = "new_event",
        textSha256 = "hash",
    )

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = AlertRepository(database.alerts())
    }

    @After
    fun close() = database.close()

    @Test
    fun everyFieldSurvivesARoundTrip() = runTest {
        val original = event("a")
        repository.insertNew(original, observation("a"))
        assertEquals(original, repository.find("a"))
    }

    @Test
    fun aMissingCellBroadcastBlockStaysNull() = runTest {
        repository.insertNew(event("a", cellBroadcast = null), observation("a"))
        assertNull(repository.find("a")!!.cellBroadcast)
    }

    @Test
    fun dedupLookupRespectsTheTimeWindow() = runTest {
        repository.insertNew(event("a"), observation("a"))
        assertEquals("a", repository.findByDedupKey(metadata.dedupKey, 4_000)?.eventId)
        assertNull(repository.findByDedupKey(metadata.dedupKey, 6_000))
    }

    @Test
    fun observedFingerprintIsFoundFromTheAuditTrailWithinTheWindow() = runTest {
        repository.insertNew(event("a"), observation("a").copy(observedAtMs = 5_000, textSha256 = "seen"))
        assertEquals("a", repository.findByObservedFingerprint("seen", 4_000)?.eventId)
        assertNull(repository.findByObservedFingerprint("seen", 6_000))
        assertNull(repository.findByObservedFingerprint("other", 0))
    }

    @Test
    fun recentEventsKeepSimulationsApart() = runTest {
        repository.insertNew(event("real"), observation("real"))
        repository.insertNew(event("sim", simulated = true), observation("sim"))
        assertEquals(listOf("real"), repository.recentEvents(0, simulated = false).map { it.eventId })
        assertEquals(listOf("sim"), repository.recentEvents(0, simulated = true).map { it.eventId })
    }

    @Test
    fun theQueueReturnsRealAlertsBeforeSimulations() = runTest {
        repository.insertNew(event("sim", simulated = true), observation("sim"))
        repository.insertNew(event("real"), observation("real"))
        assertEquals(listOf("real", "sim"), repository.dueForDelivery(10_000, 10).map { it.eventId })
    }

    @Test
    fun theQueueSkipsEventsStillBackingOff() = runTest {
        repository.insertNew(event("a").copy(nextAttemptAtMs = 9_000), observation("a"))
        assertTrue(repository.dueForDelivery(8_000, 10).isEmpty())
        assertEquals(9_000L, repository.nextAttemptAt())
    }

    @Test
    fun aDuplicateEventIdCannotBeInsertedTwice() = runTest {
        repository.insertNew(event("a"), observation("a"))
        val failed = runCatching { repository.insertNew(event("a"), observation("a")) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun evidenceAccumulatesPerKindAndProvenance() = runTest {
        repository.recordEvidence(EvidenceKind.SERVER_DELIVERY, EvidenceProvenance.SIMULATED, 1, "a", "x")
        repository.recordEvidence(EvidenceKind.SERVER_DELIVERY, EvidenceProvenance.SIMULATED, 2, "b", "y")
        repository.recordEvidence(EvidenceKind.SERVER_DELIVERY, EvidenceProvenance.LIVE_DEVICE, 3, "c", "z")
        val records = repository.observeEvidence().first()
        assertEquals(2, records.size)
        val simulated = records.first { it.provenance == EvidenceProvenance.SIMULATED }
        assertEquals(2, simulated.count)
        assertEquals(1L, simulated.firstAtMs)
        assertEquals(2L, simulated.lastAtMs)
    }

    @Test
    fun seenRowsArePersistedOnce() = runTest {
        assertFalse(repository.hasBaseline())
        repository.markSeen("1:100", 5, baseline = true)
        repository.markSeen("1:100", 6, baseline = false)
        assertTrue(repository.isSeen("1:100"))
        assertFalse(repository.isSeen("2:100"))
        assertTrue(repository.hasBaseline())
    }

    @Test
    fun theCredentialIsNeverStoredInTheClear() {
        val store = SecureCredentialStore(context)
        store.write("test_token", "s3cret-value")
        assertEquals("s3cret-value", store.read("test_token"))
        val raw = context.getSharedPreferences("receiver_credentials", Context.MODE_PRIVATE)
            .getString("test_token", null)
        assertNotEquals("s3cret-value", raw)
        assertFalse(raw!!.contains("s3cret-value"))
        store.remove("test_token")
        assertNull(store.read("test_token"))
    }
}
