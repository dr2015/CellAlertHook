package app.cellalerthook.simulation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.cellalerthook.R
import app.cellalerthook.detection.AlertSink
import app.cellalerthook.detection.NotificationAlertDetector
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.OriginTrust
import app.cellalerthook.domain.RawAlertObservation
import java.util.UUID

/**
 * Exercises the pipeline without a broadcast. Everything here is marked SIMULATED at the
 * source and reaches the server only on its test route. A pass proves the app's logic and
 * says nothing about whether a real Cell Broadcast alert can be received.
 */
class SimulationManager(
    private val context: Context,
    private val sink: AlertSink,
) {
    enum class Language { ARABIC, ENGLISH }

    /**
     * A fresh reference keeps repeated tests from being suppressed as duplicates. It is 12
     * characters because Android 15+ hides notifications holding a token of 4 to 8, like a code.
     */
    fun sampleMessage(language: Language): String {
        val reference = UUID.randomUUID().toString().replace("-", "").take(12)
        return when (language) {
            Language.ARABIC -> "[محاكاة - اختبار فقط] رسالة طوارئ تجريبية من نظام جوفلو. لا يلزم اتخاذ أي إجراء. المرجع $reference"
            Language.ENGLISH -> "[SIMULATED - TEST ONLY] CellAlertHook test emergency message. No action is required. Reference $reference"
        }
    }

    /** Test A. Needs notification access, because the listener is what is being tested. */
    fun postSimulatedNotification(language: Language) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.simulation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val message = sampleMessage(language)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(context.getString(R.string.simulation_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
        manager.notify(NotificationAlertDetector.SIMULATION_TAG, NOTIFICATION_ID, notification)
    }

    /** Test B. Proves the extraction logic, not access to the real system dialog. */
    fun launchSimulatedPopup(language: Language) {
        context.startActivity(
            Intent(context, SimulatedAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SimulatedAlertActivity.EXTRA_MESSAGE, sampleMessage(language)),
        )
    }

    /** Test C. Skips detection and tests formatting, auth, delivery, retry and queueing. */
    suspend fun sendSimulatedServerAlert(language: Language) {
        sink.submit(
            RawAlertObservation(
                method = CaptureMethod.SIMULATION,
                observedAtMs = System.currentTimeMillis(),
                title = context.getString(R.string.simulation_title),
                body = sampleMessage(language),
                originPackage = context.packageName,
                originClass = "SimulationManager",
                originTrust = OriginTrust.SELF,
                simulated = true,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "simulation"
        const val NOTIFICATION_ID = 2001
    }
}
