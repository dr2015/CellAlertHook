package app.cellalerthook.detection

import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.RawAlertObservation
import kotlinx.coroutines.flow.StateFlow

enum class DetectorState {
    ACTIVE,

    /** Switched on but something needs the operator, such as a missing grant. */
    BLOCKED,
    DISABLED,

    /** The device has no such capability. */
    UNAVAILABLE,
}

data class DetectorStatus(
    val id: String,
    val method: CaptureMethod,
    val state: DetectorState,
    val detail: String,
    val lastObservationAtMs: Long? = null,
)

/** Receives observations. Returning means the observation has been stored durably. */
fun interface AlertSink {
    suspend fun submit(observation: RawAlertObservation)

    /** Marks several alerts read together, so that anything acting on them sees the end result. */
    suspend fun batch(block: suspend () -> Unit) = block()
}

/**
 * A way of learning about an alert. New receivers such as a USB modem or a privileged
 * companion app implement this and join [DetectorRegistry]; nothing else has to change.
 */
interface AlertDetector {
    val id: String
    val method: CaptureMethod
    val status: StateFlow<DetectorStatus>

    fun start(sink: AlertSink)

    fun stop()

    /** Runs on the health tick: re-check prerequisites and pick up anything missed. */
    suspend fun refresh() = Unit
}

class DetectorRegistry(val detectors: List<AlertDetector>) {
    fun startAll(sink: AlertSink) = detectors.forEach { it.start(sink) }

    fun stopAll() = detectors.forEach { it.stop() }

    suspend fun refreshAll() = detectors.forEach { it.refresh() }
}
