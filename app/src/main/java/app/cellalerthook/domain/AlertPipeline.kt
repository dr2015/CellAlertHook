package app.cellalerthook.domain

import app.cellalerthook.data.AlertStore
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.delivery.AlertDeliveryService
import app.cellalerthook.delivery.UrlTriggerService
import app.cellalerthook.detection.AlertSink
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.service.WakeGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where every detector meets the common path: process, keep evidence, send. Processing is
 * serialized so two detectors seeing one alert at once cannot both create an event.
 */
class AlertPipeline(
    private val processor: AlertProcessor,
    private val store: AlertStore,
    private val delivery: AlertDeliveryService,
    private val trigger: UrlTriggerService,
    private val log: DiagnosticsLog,
    private val provenance: BuildProvenance,
    private val wakeGuard: WakeGuard,
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertSink {
    private val lock = Mutex()

    override suspend fun batch(block: suspend () -> Unit) = trigger.holding(block)

    override suspend fun submit(observation: RawAlertObservation) {
        wakeGuard.hold()
        val result = lock.withLock { processor.process(observation) }
        val event = when (result) {
            is ProcessingResult.Created -> result.event
            is ProcessingResult.Upgraded -> result.event
            is ProcessingResult.Duplicate -> result.event
            is ProcessingResult.Rejected -> {
                log.log(LogLevel.WARN, CATEGORY, "rejected method=${observation.method.wireName} reason=${result.reason}")
                return
            }
        }
        recordEvidence(observation, result, event.eventId, event.alertClass)
        when (result) {
            is ProcessingResult.Created, is ProcessingResult.Upgraded -> {
                log.log(LogLevel.INFO, CATEGORY, "${result.javaClass.simpleName.lowercase()} event=${event.eventId} method=${observation.method.wireName} verification=${event.verification.wireName}")
                trigger.onEvent(event)
                delivery.kick()
            }
            else -> log.log(LogLevel.INFO, CATEGORY, "duplicate event=${event.eventId} method=${observation.method.wireName} total=${event.duplicateCount}")
        }
    }

    private suspend fun recordEvidence(
        observation: RawAlertObservation,
        result: ProcessingResult,
        eventId: String,
        alertClass: AlertClass,
    ) {
        val origin = when {
            observation.simulated -> EvidenceProvenance.SIMULATED
            provenance.isProductionBuild -> EvidenceProvenance.LIVE_DEVICE
            else -> EvidenceProvenance.LAB_BUILD
        }
        val kind = when (observation.method) {
            CaptureMethod.CELL_BROADCAST_PROVIDER -> EvidenceKind.DIRECT_CELL_BROADCAST_CAPTURE
            CaptureMethod.ACCESSIBILITY -> EvidenceKind.ACCESSIBILITY_POPUP_CAPTURE
            CaptureMethod.NOTIFICATION -> EvidenceKind.NOTIFICATION_CAPTURE
            CaptureMethod.SIMULATION, CaptureMethod.EXTERNAL -> null
        }
        val now = clock()
        val detail = "origin=${observation.originPackage} trust=${observation.originTrust.name.lowercase()}"
        // A notification whose text Android hid is not a capture of the alert.
        if (observation.hiddenTextKey == null) kind?.let { store.recordEvidence(it, origin, now, eventId, detail) }

        // Only a provider row on a retail build, where no test hook exists, can count here.
        val isLiveProviderAlert = observation.method == CaptureMethod.CELL_BROADCAST_PROVIDER &&
            origin == EvidenceProvenance.LIVE_DEVICE &&
            result !is ProcessingResult.Duplicate
        if (isLiveProviderAlert) {
            store.recordEvidence(
                EvidenceKind.REAL_GOVERNMENT_ALERT,
                EvidenceProvenance.LIVE_DEVICE,
                now,
                eventId,
                "alert_class=${alertClass.wireName}",
            )
        }
    }

    private companion object {
        const val CATEGORY = "pipeline"
    }
}
