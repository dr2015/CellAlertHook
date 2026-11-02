package app.cellalerthook.detection

import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.domain.AlertFingerprint
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.OriginTrust
import app.cellalerthook.domain.RawAlertObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Just enough of an accessibility node to extract text, so extraction tests on the JVM. */
interface UiNode {
    val viewId: String?
    val className: CharSequence?
    val text: CharSequence?
    val isPassword: Boolean
    val isEditable: Boolean
    val childCount: Int
    fun child(index: Int): UiNode?
}

data class ExtractedAlert(val title: String?, val body: String, val usedKnownIds: Boolean)

/**
 * Pulls the alert out of a window. Any password or editable field means this is not an
 * alert, so extraction stops there and nothing from the window is read.
 */
class AlertWindowExtractor(
    private val maxNodes: Int = 400,
) {
    fun extract(root: UiNode, requireKnownIds: Boolean): ExtractedAlert? {
        var title: String? = null
        var body: String? = null
        val fallback = mutableListOf<String>()
        val pending = ArrayDeque<UiNode>()
        pending.addLast(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < maxNodes) {
            val node = pending.removeLast()
            visited += 1
            if (node.isPassword || node.isEditable) return null
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) {
                val id = node.viewId.orEmpty()
                when {
                    id.endsWith(MESSAGE_ID) -> body = text
                    id.endsWith(TITLE_ID) -> title = text
                    !node.className.isButton() -> fallback += text
                }
            }
            for (index in node.childCount - 1 downTo 0) {
                node.child(index)?.let { pending.addLast(it) }
            }
        }
        body?.let { return ExtractedAlert(title, it, usedKnownIds = true) }
        if (requireKnownIds || fallback.isEmpty()) return null
        val longest = fallback.maxBy { it.length }
        return ExtractedAlert(title ?: fallback.firstOrNull { it != longest }, longest, usedKnownIds = false)
    }

    private fun CharSequence?.isButton(): Boolean = this?.contains("Button") == true

    private companion object {
        const val MESSAGE_ID = ":id/message"
        const val TITLE_ID = ":id/alertTitle"
    }
}

enum class WindowDecision {
    /** A window that looks like an alert. */
    CAPTURE,

    /** Unrecognised class: accept only if the known alert view ids are present. */
    CAPTURE_IF_KNOWN_IDS,

    /** Settings, history lists and similar screens of the alert app. */
    IGNORE,
}

object AlertWindowClassifier {
    private val rejected = Regex("Settings|Preference|ListActivity|OptOut|Search|History", RegexOption.IGNORE_CASE)
    private val accepted = Regex("Alert|Emergency|Warning|Cmas|Dialog", RegexOption.IGNORE_CASE)

    fun classify(windowClass: String?): WindowDecision = when {
        windowClass == null -> WindowDecision.CAPTURE_IF_KNOWN_IDS
        rejected.containsMatchIn(windowClass) -> WindowDecision.IGNORE
        accepted.containsMatchIn(windowClass) -> WindowDecision.CAPTURE
        else -> WindowDecision.CAPTURE_IF_KNOWN_IDS
    }
}

/** The accessibility service as seen by the detector. */
interface AlertWindowSource {
    /** Root of the on-screen window owned by [packageName], if there is one. */
    fun rootOf(packageName: String): UiNode?
}

/**
 * Reads the alert window. This is a corroborating channel: text taken from UI is unverified
 * until the provider row confirms it, and it can only see what is on screen.
 *
 * It never performs an action on any node, so it cannot dismiss or alter an alert.
 */
class AccessibilityAlertDetector(
    private val selfPackage: String,
    private val packages: AlertPackageResolver,
    private val allowlist: () -> Set<String>,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertDetector {
    override val id = "accessibility"
    override val method = CaptureMethod.ACCESSIBILITY

    private val statusFlow = MutableStateFlow(statusOf(DetectorState.BLOCKED, "service_not_enabled"))
    override val status: StateFlow<DetectorStatus> = statusFlow.asStateFlow()

    private val extractor = AlertWindowExtractor()
    private val windowClasses = HashMap<String, String>()
    private var sink: AlertSink? = null
    private var source: AlertWindowSource? = null
    private var captureJob: Job? = null
    private var lastCaptureHash: String? = null
    private var lastCaptureAtMs = 0L

    override fun start(sink: AlertSink) {
        this.sink = sink
    }

    override fun stop() {
        sink = null
    }

    override suspend fun refresh() {
        val state = when {
            !isEnabled() -> statusOf(DetectorState.DISABLED, "disabled_in_settings")
            source == null -> statusOf(DetectorState.BLOCKED, "service_not_enabled")
            else -> statusOf(DetectorState.ACTIVE, "service_connected")
        }
        statusFlow.value = state.copy(lastObservationAtMs = statusFlow.value.lastObservationAtMs)
    }

    fun onServiceConnected(windowSource: AlertWindowSource) {
        source = windowSource
        statusFlow.value = statusOf(DetectorState.ACTIVE, "service_connected")
            .copy(lastObservationAtMs = statusFlow.value.lastObservationAtMs)
        log.log(LogLevel.INFO, CATEGORY, "service_connected")
    }

    fun onServiceDisconnected() {
        source = null
        statusFlow.value = statusOf(DetectorState.BLOCKED, "service_disconnected")
            .copy(lastObservationAtMs = statusFlow.value.lastObservationAtMs)
        log.log(LogLevel.WARN, CATEGORY, "service_disconnected")
    }

    /** [windowClass] is non-null only for window state changes, which name the activity. */
    fun onWindowEvent(eventName: String, packageName: String?, windowClass: String?) {
        if (packageName == null || !isEnabled()) return
        val isSelf = packageName == selfPackage
        if (!isSelf && packageName !in allowlist()) return
        if (windowClass != null) windowClasses[packageName] = windowClass
        val currentClass = windowClasses[packageName]
        if (isSelf && currentClass != SIMULATED_ACTIVITY) return

        val decision = AlertWindowClassifier.classify(currentClass)
        // Identifiers only: diagnostics must never contain screen text.
        log.log(LogLevel.DEBUG, CATEGORY, "event=$eventName pkg=$packageName class=$currentClass decision=$decision")
        if (decision == WindowDecision.IGNORE || captureJob?.isActive == true) return

        captureJob = scope.launch {
            capture(packageName, currentClass, decision == WindowDecision.CAPTURE_IF_KNOWN_IDS, isSelf)
        }
    }

    /** The window event can arrive before layout, so the first reads may find nothing. */
    private suspend fun capture(packageName: String, windowClass: String?, requireKnownIds: Boolean, simulated: Boolean) {
        for (wait in RETRY_DELAYS_MS) {
            delay(wait)
            val root = source?.rootOf(packageName) ?: continue
            val extracted = extractor.extract(root, requireKnownIds) ?: continue
            submit(packageName, windowClass, extracted, simulated)
            return
        }
        log.log(LogLevel.DEBUG, CATEGORY, "no_alert_text pkg=$packageName class=$windowClass")
    }

    private suspend fun submit(packageName: String, windowClass: String?, alert: ExtractedAlert, simulated: Boolean) {
        val now = clock()
        val hash = AlertFingerprint.of(alert.body)
        // The dialog animates and can stay up for hours on an unattended phone: one read is enough.
        // It expires with the processor's UI window, so a reissue days later is still reported.
        if (hash == lastCaptureHash && now - lastCaptureAtMs < SAME_TEXT_QUIET_MS) return
        lastCaptureHash = hash
        lastCaptureAtMs = now
        val target = sink ?: return
        target.submit(
            RawAlertObservation(
                method = CaptureMethod.ACCESSIBILITY,
                observedAtMs = now,
                title = alert.title,
                body = alert.body,
                originPackage = packageName,
                originClass = windowClass,
                originTrust = if (simulated) OriginTrust.SELF else packages.trustOf(packageName),
                simulated = simulated,
            ),
        )
        statusFlow.value = statusFlow.value.copy(lastObservationAtMs = now)
        log.log(LogLevel.INFO, CATEGORY, "alert_window_captured pkg=$packageName class=$windowClass known_ids=${alert.usedKnownIds} chars=${alert.body.length}")
    }

    private fun statusOf(state: DetectorState, detail: String) = DetectorStatus(id, method, state, detail)

    companion object {
        const val SIMULATED_ACTIVITY = "app.cellalerthook.simulation.SimulatedAlertActivity"

        private const val CATEGORY = "accessibility"
        private const val SAME_TEXT_QUIET_MS = 24 * 3_600_000L
        private val RETRY_DELAYS_MS = longArrayOf(0, 150, 350, 700, 1500)
    }
}
