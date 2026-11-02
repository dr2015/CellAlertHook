package app.cellalerthook.detection

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.res.Resources
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.cellalerthook.CellAlertHookApplication

/** No public API reports a hidden notification, so the placeholder text is the only sign of one. */
object HiddenNotificationText {
    private const val ENGLISH = "Sensitive notification content hidden"

    @SuppressLint("DiscouragedApi")
    fun placeholders(): Set<String> {
        val system = Resources.getSystem()
        val id = system.getIdentifier("redacted_notification_message", "string", "android")
        return setOfNotNull(ENGLISH, if (id != 0) system.getString(id) else null)
    }
}

/** Android binding for [NotificationAlertDetector]. Unrelated notifications stop at the classifier. */
class AlertNotificationListenerService : NotificationListenerService() {
    private val detector get() = (application as CellAlertHookApplication).container.notificationDetector

    override fun onListenerConnected() {
        super.onListenerConnected()
        val active = runCatching { activeNotifications.orEmpty().map { it.snapshot() } }.getOrDefault(emptyList())
        detector.onConnected(active)
    }

    override fun onListenerDisconnected() {
        detector.onDisconnected()
        requestRebind(ComponentName(this, AlertNotificationListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        detector.onPosted(sbn.snapshot())
    }

    private fun StatusBarNotification.snapshot(): NotificationSnapshot {
        val extras = notification.extras
        return NotificationSnapshot(
            packageName = packageName,
            channelId = notification.channelId,
            tag = tag,
            title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE),
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList().orEmpty(),
            postTimeMs = postTime,
        )
    }
}
