package app.cellalerthook.detection

import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.OriginTrust
import app.cellalerthook.domain.RawAlertObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotificationSnapshot(
    val packageName: String,
    val channelId: String?,
    val tag: String?,
    val title: CharSequence?,
    val text: CharSequence?,
    val bigText: CharSequence?,
    val lines: List<CharSequence>,
    val postTimeMs: Long,
)

enum class NotificationDecision {
    ACCEPT,
    IGNORE_PACKAGE,
    IGNORE_CHANNEL,

    /** The "N unread alerts" summary, which does not contain any alert text. */
    IGNORE_SUMMARY,
    IGNORE_EMPTY,
}

/** Decides whether a notification is an alert. Holds no Android state, so it tests on the JVM. */
object NotificationAlertClassifier {
    private const val AOSP_ALERT_CHANNEL_PREFIX = "broadcastMessages"

    fun classify(
        notification: NotificationSnapshot,
        allowed: Set<String>,
        selfPackage: String,
        simulationTag: String,
    ): NotificationDecision {
        val isSimulation = notification.packageName == selfPackage && notification.tag == simulationTag
        if (notification.packageName !in allowed && !isSimulation) return NotificationDecision.IGNORE_PACKAGE
        if (notification.packageName in KnownAlertPackages.AOSP_DERIVED) {
            if (notification.channelId?.startsWith(AOSP_ALERT_CHANNEL_PREFIX) != true) {
                return NotificationDecision.IGNORE_CHANNEL
            }
            if (notification.bigText.isNullOrBlank()) return NotificationDecision.IGNORE_SUMMARY
        }
        return if (bodyOf(notification).isNullOrBlank()) NotificationDecision.IGNORE_EMPTY else NotificationDecision.ACCEPT
    }

    fun bodyOf(notification: NotificationSnapshot): CharSequence? =
        notification.bigText?.takeIf { it.isNotBlank() }
            ?: notification.lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: notification.text

    /** Non-null when the body is Android's placeholder. The post time tells two such alerts apart. */
    fun hiddenTextKey(notification: NotificationSnapshot, placeholders: Set<String>): String? =
        bodyOf(notification)?.toString()?.trim()
            ?.takeIf { it in placeholders }
            ?.let { "hidden|${notification.packageName}|${notification.postTimeMs}" }
}

/**
 * Reads alert notifications. On stock Android this is the weakest channel: an emergency
 * alert shows a full-screen dialog and posts no notification unless the user leaves that
 * dialog. See docs/HOW_IT_WORKS.md for the measurement.
 */
class NotificationAlertDetector(
    private val selfPackage: String,
    private val packages: AlertPackageResolver,
    private val allowlist: () -> Set<String>,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val hiddenTextPlaceholders: () -> Set<String>,
    private val textDetectorActive: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertDetector {
    override val id = "notification"
    override val method = CaptureMethod.NOTIFICATION

    private val statusFlow = MutableStateFlow(statusOf(DetectorState.BLOCKED, "listener_not_enabled"))
    override val status: StateFlow<DetectorStatus> = statusFlow.asStateFlow()

    private var sink: AlertSink? = null
    private var connected = false

    override fun start(sink: AlertSink) {
        this.sink = sink
    }

    override fun stop() {
        sink = null
    }

    override suspend fun refresh() {
        val state = when {
            !isEnabled() -> statusOf(DetectorState.DISABLED, "disabled_in_settings")
            !connected -> statusOf(DetectorState.BLOCKED, "listener_not_enabled")
            else -> statusOf(DetectorState.ACTIVE, "listener_connected")
        }
        statusFlow.value = state.copy(lastObservationAtMs = statusFlow.value.lastObservationAtMs)
    }

    fun onConnected(active: List<NotificationSnapshot>) {
        connected = true
        statusFlow.value = statusOf(DetectorState.ACTIVE, "listener_connected")
            .copy(lastObservationAtMs = statusFlow.value.lastObservationAtMs)
        log.log(LogLevel.INFO, CATEGORY, "listener_connected")
        // A notification can sit in the shade for days. Anything older has already been handled
        // or is covered by the provider, and resending it would look like a new incident.
        val now = clock()
        active.filter { now - it.postTimeMs < CATCH_UP_MAX_AGE_MS }.forEach { onPosted(it, catchUp = true) }
    }

    fun onDisconnected() {
        connected = false
        statusFlow.value = statusOf(DetectorState.BLOCKED, "listener_disconnected")
            .copy(lastObservationAtMs = statusFlow.value.lastObservationAtMs)
        log.log(LogLevel.WARN, CATEGORY, "listener_disconnected")
    }

    fun onPosted(notification: NotificationSnapshot, catchUp: Boolean = false) {
        if (!isEnabled()) return
        val decision = NotificationAlertClassifier.classify(notification, allowlist(), selfPackage, SIMULATION_TAG)
        if (decision == NotificationDecision.IGNORE_PACKAGE) return
        val hiddenTextKey = if (decision == NotificationDecision.ACCEPT) {
            NotificationAlertClassifier.hiddenTextKey(notification, hiddenTextPlaceholders())
        } else {
            null
        }
        // Identifiers only: diagnostics must never contain notification text.
        log.log(LogLevel.INFO, CATEGORY, "pkg=${notification.packageName} channel=${notification.channelId} decision=$decision hidden=${hiddenTextKey != null}")
        if (decision != NotificationDecision.ACCEPT) return

        val simulated = notification.packageName == selfPackage
        val now = clock()
        scope.launch {
            sink?.submit(
                RawAlertObservation(
                    method = CaptureMethod.NOTIFICATION,
                    observedAtMs = now,
                    title = notification.title?.toString(),
                    body = NotificationAlertClassifier.bodyOf(notification)?.toString(),
                    originPackage = notification.packageName,
                    originClass = notification.channelId,
                    originTrust = if (simulated) OriginTrust.SELF else packages.trustOf(notification.packageName),
                    simulated = simulated,
                    catchUp = catchUp,
                    hiddenTextKey = hiddenTextKey,
                    textDetectorActive = textDetectorActive(),
                ),
            )
            statusFlow.value = statusFlow.value.copy(lastObservationAtMs = now)
        }
    }

    private fun statusOf(state: DetectorState, detail: String) = DetectorStatus(id, method, state, detail)

    companion object {
        const val SIMULATION_TAG = "cellalerthook_simulation"
        private const val CATEGORY = "notification"
        private const val CATCH_UP_MAX_AGE_MS = 24 * 3_600_000L
    }
}
