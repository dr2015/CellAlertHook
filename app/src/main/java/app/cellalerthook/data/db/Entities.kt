package app.cellalerthook.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.cellalerthook.domain.AlertClass
import app.cellalerthook.domain.AlertSource
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.CellBroadcastMetadata
import app.cellalerthook.domain.Confidence
import app.cellalerthook.domain.DeliveryStatus
import app.cellalerthook.domain.ObservationOutcome
import app.cellalerthook.domain.Severity
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.VerificationStatus

/** One incident. Repeats of the same alert add observations, never new events. */
@Entity(
    tableName = "alert_events",
    indices = [
        Index("dedupKey"),
        Index("detectedAtMs"),
        Index("deliveryStatus", "nextAttemptAtMs"),
    ],
)
data class AlertEvent(
    @PrimaryKey val eventId: String,
    val deviceId: String,
    val detectedAtMs: Long,
    val receivedAtMs: Long?,
    val title: String?,
    val message: String,
    val language: String,
    val captureMethod: CaptureMethod,
    val source: AlertSource,
    val originPackage: String?,
    val originClass: String?,
    val alertClass: AlertClass,
    val severity: Severity,
    val verification: VerificationStatus,
    val confidence: Confidence,
    val validationNotes: List<String>,
    val simulated: Boolean,
    val catchUp: Boolean,
    val dedupKey: String?,
    val fingerprint: String,
    val revision: Int,
    val duplicateCount: Int,
    val lastObservedAtMs: Long,
    val corroboratingMethods: Set<CaptureMethod>,
    @Embedded(prefix = "cb_") val cellBroadcast: CellBroadcastMetadata?,
    val deliveryStatus: DeliveryStatus,
    val deliveryAttempts: Int,
    val nextAttemptAtMs: Long,
    val deliveredAtMs: Long?,
    val lastDeliveryError: String?,
    val lastHttpStatus: Int?,
)

enum class TriggerStatus {
    PENDING,
    SENT,

    /** A newer alert replaced it, so it must not be sent. */
    SUPERSEDED,

    /** The target was already in this state. */
    REPEAT,

    /** Too old to fire by the time it could be sent. */
    EXPIRED,

    /** Arrived outside active hours, too long before they begin to still matter. */
    OUTSIDE_HOURS,
}

/** One requested change to the target system. At most one is ever pending. */
@Entity(tableName = "trigger_commands", indices = [Index("status"), Index("alertReceivedAtMs")])
data class TriggerCommand(
    @PrimaryKey val eventId: String,
    val action: TriggerAction,
    /** When the phone received the alert. This orders commands, not when the app read it. */
    val alertReceivedAtMs: Long,
    val createdAtMs: Long,
    val status: TriggerStatus,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long,
    val sentAtMs: Long? = null,
    val lastHttpStatus: Int? = null,
    val lastError: String? = null,
)

/** Audit trail of every observation, including the ones suppressed as duplicates. */
@Entity(tableName = "alert_observations", indices = [Index("eventId"), Index("observedAtMs")])
data class AlertObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String?,
    val observedAtMs: Long,
    val method: CaptureMethod,
    val originPackage: String?,
    val originClass: String?,
    val outcome: ObservationOutcome,
    val detail: String,
    val textSha256: String?,
)

@Entity(tableName = "delivery_attempts", indices = [Index("eventId"), Index("startedAtMs")])
data class DeliveryAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val revision: Int,
    val startedAtMs: Long,
    val durationMs: Long,
    val endpoint: String,
    val httpStatus: Int?,
    val outcome: String,
    val detail: String?,
)

@Entity(tableName = "diagnostic_entries", indices = [Index("atMs")])
data class DiagnosticEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
)

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** The separate capabilities the project has to prove. */
enum class EvidenceKind {
    NOTIFICATION_CAPTURE,
    ACCESSIBILITY_POPUP_CAPTURE,
    DIRECT_CELL_BROADCAST_CAPTURE,
    REAL_GOVERNMENT_ALERT,
    SERVER_DELIVERY,
}

/** Keeps a simulated pass from ever reading as a field result. */
enum class EvidenceProvenance {
    SIMULATED,

    /** Real pipeline on a debuggable build, where a PDU may have been injected in a lab. */
    LAB_BUILD,

    /** Real pipeline on a retail build. */
    LIVE_DEVICE,
}

@Entity(tableName = "evidence", primaryKeys = ["kind", "provenance"])
data class EvidenceRecord(
    val kind: EvidenceKind,
    val provenance: EvidenceProvenance,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val count: Int,
    val lastEventId: String?,
    val detail: String?,
)

/** Provider rows already handled, so a restart replays nothing and misses nothing. */
@Entity(tableName = "seen_cell_broadcast_rows")
data class SeenCellBroadcastRow(
    @PrimaryKey val rowKey: String,
    val firstSeenAtMs: Long,
    val baseline: Boolean,
)
