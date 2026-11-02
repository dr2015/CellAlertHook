package app.cellalerthook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.cellalerthook.CellAlertHookApplication
import app.cellalerthook.R
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process, and so the provider observer and the send path, alive with the app
 * closed. Sockets stay usable in Doze because a foreground service is exempt from its
 * network block. It runs no alert logic of its own.
 */
class ReceiverForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container get() = (application as CellAlertHookApplication).container

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        container.health.serviceRunning = true
        container.diagnostics.log(LogLevel.INFO, CATEGORY, "service_started")
        scope.launch { tickLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        container.health.serviceRunning = false
        container.diagnostics.log(LogLevel.WARN, CATEGORY, "service_stopped")
        scope.cancel()
        super.onDestroy()
    }

    /** The loop outlives any single failure: a bad tick must not end monitoring. */
    private suspend fun tickLoop() {
        while (scope.isActive) {
            try {
                container.refreshAllowlist()
                container.registry.refreshAll()
                container.delivery.drain()
                container.health.tick()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                container.diagnostics.log(LogLevel.ERROR, CATEGORY, "tick_failed ${e.javaClass.simpleName}")
            }
            delay(TICK_MS)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "receiver_status"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 15_000L
        private const val CATEGORY = "service"

        /** Android may refuse from the background; the watchdog retries, so it is not fatal. */
        fun start(context: Context, log: DiagnosticsLog) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReceiverForegroundService::class.java))
            } catch (e: IllegalStateException) {
                log.log(LogLevel.WARN, CATEGORY, "start_refused ${e.javaClass.simpleName}")
            }
        }
    }
}

/**
 * LOCKED_BOOT_COMPLETED arrives before the first unlock, so the receiver is running even if nobody
 * unlocks a restarted phone. Android 15 forbids some service types from starting at boot;
 * specialUse is not one of them.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val container = (context.applicationContext as CellAlertHookApplication).container
                container.diagnostics.log(LogLevel.INFO, "service", "boot_receiver action=${intent.action}")
                container.onUserUnlocked()
                ReceiverForegroundService.start(context, container.diagnostics)
            }
        }
    }
}
