package app.cellalerthook

import app.cellalerthook.data.AlertStore
import app.cellalerthook.data.TriggerStore
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.AlertObservation
import app.cellalerthook.data.db.DeliveryAttempt
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.data.db.TriggerCommand
import app.cellalerthook.data.db.TriggerStatus
import app.cellalerthook.domain.AlertProcessor
import app.cellalerthook.domain.BuildProvenance
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.CellBroadcastMetadata
import app.cellalerthook.domain.DeliveryStatus
import app.cellalerthook.domain.OriginTrust
import app.cellalerthook.domain.ProcessingResult
import app.cellalerthook.domain.ProcessorSettings
import app.cellalerthook.domain.RawAlertObservation

class FakeClock(var nowMs: Long = 1_800_000_000_000L) {
    val read: () -> Long = { nowMs }
    fun advance(ms: Long) {
        nowMs += ms
    }
}

class FakeStore : AlertStore {
    val events = LinkedHashMap<String, AlertEvent>()
    val observations = mutableListOf<AlertObservation>()
    val attempts = mutableListOf<DeliveryAttempt>()
    val evidence = mutableListOf<Pair<EvidenceKind, EvidenceProvenance>>()

    override suspend fun find(eventId: String) = events[eventId]

    override suspend fun findByDedupKey(key: String, sinceMs: Long) =
        events.values.lastOrNull { it.dedupKey == key && it.detectedAtMs >= sinceMs }

    override suspend fun recentEvents(sinceMs: Long, simulated: Boolean) =
        events.values.filter { it.detectedAtMs >= sinceMs && it.simulated == simulated }

    override suspend fun findByObservedFingerprint(fingerprint: String, sinceMs: Long) =
        observations.lastOrNull { it.textSha256 == fingerprint && it.observedAtMs >= sinceMs }?.eventId?.let(events::get)

    override suspend fun insertNew(event: AlertEvent, observation: AlertObservation) {
        events[event.eventId] = event
        observations += observation
    }

    override suspend fun updateWithObservation(event: AlertEvent, observation: AlertObservation) {
        events[event.eventId] = event
        observations += observation
    }

    override suspend fun recordRejected(observation: AlertObservation) {
        observations += observation
    }

    override suspend fun update(event: AlertEvent) {
        events[event.eventId] = event
    }

    override suspend fun dueForDelivery(nowMs: Long, limit: Int) = events.values
        .filter { it.deliveryStatus == DeliveryStatus.PENDING && it.nextAttemptAtMs <= nowMs }
        .take(limit)

    override suspend fun nextAttemptAt() = events.values
        .filter { it.deliveryStatus == DeliveryStatus.PENDING }
        .minOfOrNull { it.nextAttemptAtMs }

    override suspend fun recordAttempt(attempt: DeliveryAttempt) {
        attempts += attempt
    }

    override suspend fun recordEvidence(
        kind: EvidenceKind,
        provenance: EvidenceProvenance,
        atMs: Long,
        eventId: String?,
        detail: String?,
    ) {
        evidence += kind to provenance
    }
}

class FakeTriggerStore : TriggerStore {
    val commands = LinkedHashMap<String, TriggerCommand>()

    private fun newest(filter: (TriggerCommand) -> Boolean) = commands.values.filter(filter)
        .maxWithOrNull(compareBy({ it.alertReceivedAtMs }, { it.createdAtMs }))

    override suspend fun findCommand(eventId: String) = commands[eventId]

    override suspend fun latestCommand() = newest { true }

    override suspend fun latestEffectiveCommand() =
        newest { it.status == TriggerStatus.PENDING || it.status == TriggerStatus.SENT }

    override suspend fun pendingCommand() = newest { it.status == TriggerStatus.PENDING }

    override suspend fun supersedePendingCommands() {
        commands.replaceAll { _, command ->
            if (command.status == TriggerStatus.PENDING) command.copy(status = TriggerStatus.SUPERSEDED) else command
        }
    }

    override suspend fun insertCommand(command: TriggerCommand) {
        commands[command.eventId] = command
    }

    override suspend fun updateCommand(command: TriggerCommand) {
        commands[command.eventId] = command
    }
}

val RETAIL_BUILD = BuildProvenance("user", "release-keys")
val LAB_BUILD = BuildProvenance("userdebug", "dev-keys")

fun testSettings() = ProcessorSettings(
    deviceId = "cellalerthook-test",
    duplicateWindowMs = 30 * 60_000L,
    cellBroadcastDuplicateWindowMs = 24 * 3_600_000L,
)

fun newProcessor(
    store: FakeStore,
    clock: FakeClock,
    provenance: BuildProvenance = RETAIL_BUILD,
): AlertProcessor {
    var next = 0
    return AlertProcessor(store, provenance, ::testSettings, clock.read) {
        next += 1
        "event-$next"
    }
}

fun cellBroadcastMetadata(
    messageId: Int = 4370,
    serial: Int = 0xC290,
    plmn: String? = "42001",
    cmasSeverity: Int? = -1,
    receivedTimeMs: Long = 1_799_999_999_000L,
) = CellBroadcastMetadata(
    rowId = 1,
    messageId = messageId,
    serialNumber = serial,
    plmn = plmn,
    lac = 8514,
    cid = 47108,
    slotIndex = 0,
    subscriptionId = 1,
    receivedTimeMs = receivedTimeMs,
    messageFormat = 1,
    messagePriority = 3,
    dataCodingScheme = 17,
    cmasMessageClass = 0,
    cmasCategory = -1,
    cmasResponseType = -1,
    cmasSeverity = cmasSeverity,
    cmasUrgency = -1,
    cmasCertainty = -1,
    etwsWarningType = null,
    displayed = true,
)

const val ARABIC_ALERT = "تنبيه طوارئ من الدفاع المدني. يرجى اتباع التعليمات الرسمية."

fun providerObservation(
    body: String? = ARABIC_ALERT,
    metadata: CellBroadcastMetadata = cellBroadcastMetadata(),
    observedAtMs: Long = 1_800_000_000_000L,
    catchUp: Boolean = false,
) = RawAlertObservation(
    method = CaptureMethod.CELL_BROADCAST_PROVIDER,
    observedAtMs = observedAtMs,
    title = null,
    body = body,
    originPackage = "com.google.android.cellbroadcastservice",
    originClass = "CellBroadcastProvider",
    originTrust = OriginTrust.SYSTEM_IMAGE,
    languageHint = "ar",
    cellBroadcast = metadata,
    catchUp = catchUp,
)

fun uiObservation(
    body: String? = ARABIC_ALERT,
    method: CaptureMethod = CaptureMethod.ACCESSIBILITY,
    trust: OriginTrust = OriginTrust.SYSTEM_IMAGE,
    title: String? = "National alert",
    originPackage: String = "com.google.android.cellbroadcastreceiver",
    simulated: Boolean = false,
) = RawAlertObservation(
    method = method,
    observedAtMs = 1_800_000_000_000L,
    title = title,
    body = body,
    originPackage = originPackage,
    originClass = "com.android.cellbroadcastreceiver.CellBroadcastAlertDialog",
    originTrust = trust,
    simulated = simulated,
)

/** A notification whose text Android replaced. Each post of one has its own key. */
fun hiddenObservation(
    postTimeMs: Long = 1,
    simulated: Boolean = false,
    textDetectorActive: Boolean = true,
    observedAtMs: Long = 1_800_000_000_000L,
) = uiObservation(
    body = "Sensitive notification content hidden",
    method = CaptureMethod.NOTIFICATION,
    title = "Wireless Emergency Alerts",
    simulated = simulated,
).copy(
    hiddenTextKey = "hidden|com.google.android.cellbroadcastreceiver|$postTimeMs",
    textDetectorActive = textDetectorActive,
    observedAtMs = observedAtMs,
)

fun ProcessingResult.created(): AlertEvent = (this as ProcessingResult.Created).event
