package app.cellalerthook.detection

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.cellalerthook.CellAlertHookApplication

/**
 * Thin Android binding for [AccessibilityAlertDetector]. Events are restricted to the
 * allowlisted alert packages, so the service never receives other apps' screens.
 */
class AlertAccessibilityService : AccessibilityService(), AlertWindowSource {
    private val container get() = (application as CellAlertHookApplication).container
    private val detector get() = container.accessibilityDetector

    override fun onServiceConnected() {
        super.onServiceConnected()
        val allowed = container.packages.effectiveAllowlist(container.configuration.current().packageAllowlist)
        val self = packageName
        serviceInfo = serviceInfo.apply { packageNames = (allowed + self).toTypedArray() }
        detector.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val name = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows_changed"
            else -> return
        }
        val windowClass = event.className?.toString()
            ?.takeIf { event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        detector.onWindowEvent(name, event.packageName?.toString(), windowClass)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        detector.onServiceDisconnected()
        super.onDestroy()
    }

    override fun rootOf(packageName: String): UiNode? {
        val fromWindows = windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == packageName }
        }
        val root = fromWindows ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }
        return root?.let(::AccessibilityUiNode)
    }

    private class AccessibilityUiNode(private val node: AccessibilityNodeInfo) : UiNode {
        override val viewId: String? get() = node.viewIdResourceName
        override val className: CharSequence? get() = node.className
        override val text: CharSequence? get() = node.text
        override val isPassword: Boolean get() = node.isPassword
        override val isEditable: Boolean get() = node.isEditable
        override val childCount: Int get() = node.childCount

        override fun child(index: Int): UiNode? = node.getChild(index)?.let(::AccessibilityUiNode)
    }
}
