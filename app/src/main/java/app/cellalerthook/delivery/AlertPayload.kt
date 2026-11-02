package app.cellalerthook.delivery

import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.HiddenAlertText
import app.cellalerthook.domain.VerificationStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter

@Serializable
data class VerificationPayload(
    val status: String,
    val confidence: String,
    val notes: List<String>,
)

@Serializable
data class CellBroadcastPayload(
    val messageId: Int,
    val serialNumber: Int,
    val messageCode: Int,
    val updateNumber: Int,
    val geographicalScope: Int,
    val plmn: String?,
    val lac: Int?,
    val cid: Int?,
    val priority: Int?,
    val dataCodingScheme: Int?,
    val cmasMessageClass: Int?,
    val cmasSeverity: Int?,
    val cmasUrgency: Int?,
    val cmasCertainty: Int?,
    val etwsWarningType: Int?,
    val displayedBySystem: Boolean?,
)

/**
 * Wire format. The first group of fields is the agreed contract; the rest is additive.
 *
 * `verified` means only that the OS Cell Broadcast stack delivered the message on a retail
 * build. Cell Broadcast is unsigned, so the server must not read it as proof of authorship.
 */
@Serializable
data class AlertPayload(
    val eventId: String,
    val deviceId: String,
    val timestamp: String,
    val source: String,
    val captureMethod: String,
    val originPackage: String?,
    val title: String?,
    val message: String,
    val language: String,
    val severity: String,
    val verified: Boolean,
    val simulation: Boolean,
    val schemaVersion: Int = 1,
    /** Same eventId with a higher revision replaces an earlier record of the incident. */
    val revision: Int,
    val receivedAt: String?,
    val alertClass: String,
    val testOrExercise: Boolean?,
    val catchUp: Boolean,
    val duplicateObservations: Int,
    val corroboratedBy: List<String>,
    val verification: VerificationPayload,
    val cellBroadcast: CellBroadcastPayload?,
    /** True when Android hid the alert from this app, so `message` is a note and not the alert. */
    val textHidden: Boolean,
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        fun from(event: AlertEvent): AlertPayload = AlertPayload(
            eventId = event.eventId,
            deviceId = event.deviceId,
            timestamp = iso(event.detectedAtMs),
            source = event.source.wireName,
            captureMethod = event.captureMethod.wireName,
            originPackage = event.originPackage,
            title = event.title,
            message = event.message,
            language = event.language,
            severity = event.severity.wireName,
            verified = event.verification == VerificationStatus.CELL_BROADCAST_STACK && !event.simulated,
            simulation = event.simulated,
            revision = event.revision,
            receivedAt = event.receivedAtMs?.let(::iso),
            alertClass = event.alertClass.wireName,
            testOrExercise = event.alertClass.testOrExercise,
            catchUp = event.catchUp,
            duplicateObservations = event.duplicateCount,
            corroboratedBy = event.corroboratingMethods.map(CaptureMethod::wireName).sorted(),
            verification = VerificationPayload(
                status = event.verification.wireName,
                confidence = event.confidence.wireName,
                notes = event.validationNotes,
            ),
            cellBroadcast = event.cellBroadcast?.let {
                CellBroadcastPayload(
                    messageId = it.messageId,
                    serialNumber = it.serialNumber,
                    messageCode = it.messageCode,
                    updateNumber = it.updateNumber,
                    geographicalScope = it.geographicalScope,
                    plmn = it.plmn,
                    lac = it.lac,
                    cid = it.cid,
                    priority = it.messagePriority,
                    dataCodingScheme = it.dataCodingScheme,
                    cmasMessageClass = it.cmasMessageClass,
                    cmasSeverity = it.cmasSeverity,
                    cmasUrgency = it.cmasUrgency,
                    cmasCertainty = it.cmasCertainty,
                    etwsWarningType = it.etwsWarningType,
                    displayedBySystem = it.displayed,
                )
            },
            textHidden = event.message == HiddenAlertText.MESSAGE,
        )

        private fun iso(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
    }
}

/** What the server returns to confirm it has stored the event. */
@Serializable
data class DeliveryAck(
    val ack: Boolean = false,
    val eventId: String? = null,
    val revision: Int? = null,
    val duplicate: Boolean = false,
)

@Serializable
data class HeartbeatPayload(
    val deviceId: String,
    val timestamp: String,
    val appVersion: String,
    val androidSdk: Int,
    val pendingEvents: Int,
    val detectors: Map<String, String>,
) {
    fun toJson(): String = Json.encodeToString(this)
}
