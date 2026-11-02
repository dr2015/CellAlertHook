package app.cellalerthook.delivery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.cellalerthook.CellAlertHookApplication
import app.cellalerthook.config.DeviceStorage
import app.cellalerthook.detection.CellBroadcastProviderDetector
import app.cellalerthook.service.ReceiverForegroundService
import java.util.concurrent.TimeUnit

/**
 * Durable delivery fallback. WorkManager is for surviving process death, not for speed:
 * the foreground service and network callback handle the immediate path.
 */
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CellAlertHookApplication).container
        container.delivery.drain()
        return Result.success()
    }
}

/** Durable retry of a trigger command, for when this process died before its own timer ran. */
class TriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CellAlertHookApplication).container
        container.trigger.send()
        return Result.success()
    }
}

/** Restarts the foreground service if the OS or an OEM task killer removed it. */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CellAlertHookApplication).container
        ReceiverForegroundService.start(applicationContext, container.diagnostics)
        container.registry.refreshAll()
        container.trigger.send()
        container.delivery.drain()
        container.diagnostics.prune()
        return Result.success()
    }
}

/**
 * Runs when the Cell Broadcast provider changes even if this process is not running.
 * The content trigger is one-shot, so each run schedules the next.
 */
class CellBroadcastTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CellAlertHookApplication).container
        container.providerDetector.refresh()
        container.trigger.send()
        container.delivery.drain()
        WorkScheduler.scheduleProviderTrigger(applicationContext)
        return Result.success()
    }
}

object WorkScheduler {
    private const val DELIVERY_WORK = "cellalerthook-delivery"
    private const val TRIGGER_WORK = "cellalerthook-trigger"
    private const val WATCHDOG_WORK = "cellalerthook-watchdog"
    private const val PROVIDER_TRIGGER_WORK = "cellalerthook-provider-trigger"
    private const val WATCHDOG_MINUTES = 15L

    /** Null before the first unlock, because WorkManager keeps its database in normal storage. */
    private fun workManager(context: Context): WorkManager? =
        if (DeviceStorage.isUserUnlocked(context)) WorkManager.getInstance(context) else null

    fun scheduleDelivery(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager(context)?.enqueueUniqueWork(DELIVERY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleTrigger(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<TriggerWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager(context)?.enqueueUniqueWork(TRIGGER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_MINUTES, TimeUnit.MINUTES).build()
        workManager(context)?.enqueueUniquePeriodicWork(WATCHDOG_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleProviderTrigger(context: Context) {
        val request = OneTimeWorkRequestBuilder<CellBroadcastTriggerWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CellBroadcastProviderDetector.ROOT_URI, true)
                    .build(),
            )
            .build()
        workManager(context)?.enqueueUniqueWork(PROVIDER_TRIGGER_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
