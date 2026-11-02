package app.cellalerthook.data

import app.cellalerthook.data.db.AlertDao
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.AlertObservation
import app.cellalerthook.data.db.DeliveryAttempt
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.data.db.EvidenceRecord
import app.cellalerthook.data.db.SeenCellBroadcastRow
import app.cellalerthook.data.db.TriggerCommand
import app.cellalerthook.domain.DeliveryStatus
import kotlinx.coroutines.flow.Flow

/** Persistence used by the pipeline. The interface keeps the pipeline testable on the JVM. */
interface AlertStore {
    suspend fun find(eventId: String): AlertEvent?
    suspend fun findByDedupKey(key: String, sinceMs: Long): AlertEvent?
    suspend fun recentEvents(sinceMs: Long, simulated: Boolean): List<AlertEvent>

    /** Looks in the audit trail, so it also finds an observation that was suppressed as a duplicate. */
    suspend fun findByObservedFingerprint(fingerprint: String, sinceMs: Long): AlertEvent?
    suspend fun insertNew(event: AlertEvent, observation: AlertObservation)
    suspend fun updateWithObservation(event: AlertEvent, observation: AlertObservation)
    suspend fun recordRejected(observation: AlertObservation)
    suspend fun update(event: AlertEvent)
    suspend fun dueForDelivery(nowMs: Long, limit: Int): List<AlertEvent>
    suspend fun nextAttemptAt(): Long?
    suspend fun recordAttempt(attempt: DeliveryAttempt)
    suspend fun recordEvidence(
        kind: EvidenceKind,
        provenance: EvidenceProvenance,
        atMs: Long,
        eventId: String?,
        detail: String?,
    )
}

/** The commands sent to the target system. Holds at most one pending command. */
interface TriggerStore {
    suspend fun findCommand(eventId: String): TriggerCommand?

    /** The newest command by alert time, whatever became of it. */
    suspend fun latestCommand(): TriggerCommand?

    /** The newest command that reached, or is still trying to reach, the target. */
    suspend fun latestEffectiveCommand(): TriggerCommand?
    suspend fun pendingCommand(): TriggerCommand?
    suspend fun supersedePendingCommands()
    suspend fun insertCommand(command: TriggerCommand)
    suspend fun updateCommand(command: TriggerCommand)
}

/** Rows of the system provider that have already been handled. */
interface SeenRowStore {
    suspend fun isSeen(rowKey: String): Boolean
    suspend fun markSeen(rowKey: String, atMs: Long, baseline: Boolean)
    suspend fun hasBaseline(): Boolean
}

class AlertRepository(private val dao: AlertDao) : AlertStore, TriggerStore, SeenRowStore {
    fun observeEvents(limit: Int = 200): Flow<List<AlertEvent>> = dao.observeEvents(limit)
    fun observeCommands(limit: Int = 200): Flow<List<TriggerCommand>> = dao.observeCommands(limit)
    fun observeObservations(eventId: String): Flow<List<AlertObservation>> = dao.observeObservations(eventId)
    fun observePendingCount(): Flow<Int> = dao.observeCount(DeliveryStatus.PENDING)
    fun observeLastRealDetection(): Flow<Long?> = dao.observeLastRealDetection()
    fun observeLastDelivery(): Flow<Long?> = dao.observeLastDelivery()
    fun observeAttempts(limit: Int = 50): Flow<List<DeliveryAttempt>> = dao.observeAttempts(limit)
    fun observeEvidence(): Flow<List<EvidenceRecord>> = dao.observeEvidence()

    override suspend fun find(eventId: String): AlertEvent? = dao.find(eventId)

    override suspend fun findByDedupKey(key: String, sinceMs: Long): AlertEvent? =
        dao.findByDedupKey(key, sinceMs)

    override suspend fun recentEvents(sinceMs: Long, simulated: Boolean): List<AlertEvent> =
        dao.recent(sinceMs, simulated)

    override suspend fun findByObservedFingerprint(fingerprint: String, sinceMs: Long): AlertEvent? =
        dao.findByObservedFingerprint(fingerprint, sinceMs)

    override suspend fun insertNew(event: AlertEvent, observation: AlertObservation) =
        dao.insertWithObservation(event, observation)

    override suspend fun updateWithObservation(event: AlertEvent, observation: AlertObservation) =
        dao.updateWithObservation(event, observation)

    override suspend fun recordRejected(observation: AlertObservation) {
        dao.insertObservation(observation)
    }

    override suspend fun update(event: AlertEvent) = dao.update(event)

    override suspend fun dueForDelivery(nowMs: Long, limit: Int): List<AlertEvent> =
        dao.due(DeliveryStatus.PENDING, nowMs, limit)

    override suspend fun nextAttemptAt(): Long? = dao.nextAttemptAt(DeliveryStatus.PENDING)

    override suspend fun recordAttempt(attempt: DeliveryAttempt) = dao.insertAttempt(attempt)

    override suspend fun recordEvidence(
        kind: EvidenceKind,
        provenance: EvidenceProvenance,
        atMs: Long,
        eventId: String?,
        detail: String?,
    ) {
        val existing = dao.findEvidence(kind, provenance)
        dao.upsertEvidence(
            EvidenceRecord(
                kind = kind,
                provenance = provenance,
                firstAtMs = existing?.firstAtMs ?: atMs,
                lastAtMs = atMs,
                count = (existing?.count ?: 0) + 1,
                lastEventId = eventId,
                detail = detail,
            ),
        )
    }

    override suspend fun findCommand(eventId: String): TriggerCommand? = dao.findCommand(eventId)

    override suspend fun latestCommand(): TriggerCommand? = dao.latestCommand()

    override suspend fun latestEffectiveCommand(): TriggerCommand? = dao.latestEffectiveCommand()

    override suspend fun pendingCommand(): TriggerCommand? = dao.pendingCommand()

    override suspend fun supersedePendingCommands() = dao.supersedePendingCommands()

    override suspend fun insertCommand(command: TriggerCommand) = dao.insertCommand(command)

    override suspend fun updateCommand(command: TriggerCommand) = dao.updateCommand(command)

    override suspend fun isSeen(rowKey: String): Boolean = dao.seenCount(rowKey) > 0

    override suspend fun markSeen(rowKey: String, atMs: Long, baseline: Boolean) =
        dao.markSeen(SeenCellBroadcastRow(rowKey, atMs, baseline))

    override suspend fun hasBaseline(): Boolean = dao.seenTotal() > 0
}
