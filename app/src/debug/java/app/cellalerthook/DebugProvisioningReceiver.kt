package app.cellalerthook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import app.cellalerthook.config.DeviceStorage
import app.cellalerthook.detection.CellBroadcastProviderDetector
import app.cellalerthook.detection.CellBroadcastRowMapper
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.TriggerMatch
import app.cellalerthook.domain.TriggerMethod
import app.cellalerthook.domain.TriggerRules
import app.cellalerthook.domain.TriggerSettings
import app.cellalerthook.simulation.SimulationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lets scripted tests configure the app and press the simulation buttons from adb.
 * It lives in the debug source set, so a release build does not contain it at all.
 */
class DebugProvisioningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as CellAlertHookApplication).container
        when (intent.action) {
            ACTION_PROVISION -> provision(container, context, intent)
            ACTION_SIMULATE -> simulate(container, intent)
            ACTION_REPLAY -> replay(container, context, intent)
            ACTION_CHECK_URL -> checkUrl(container, intent)
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> bootProbe(container, context)
        }
    }

    /**
     * Connects and hangs up with no request, so it can be pointed at a live server. Give it only
     * the scheme, host and port: adb commands appear in a Samsung's logcat, so a link's path and
     * token must never go on the command line.
     */
    private fun checkUrl(container: AppContainer, intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        container.appScope.launch {
            val result = runCatching { container.transport.checkConnection(url) }
            Log.i(TAG, "url_check result=${result.exceptionOrNull()?.javaClass?.simpleName ?: "ok"} detail=${result.exceptionOrNull()?.cause?.javaClass?.simpleName}")
        }
    }

    private fun probePreferences(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences("debug_boot_probe", Context.MODE_PRIVATE)

    private fun storedMatches(context: Context, settings: TriggerSettings): List<TriggerMatch> = context.contentResolver
        .query(CellBroadcastProviderDetector.HISTORY_URI, null, null, null, CellBroadcastRowMapper.SORT_NEWEST_FIRST)
        ?.use { cursor ->
            val body = cursor.getColumnIndexOrThrow("body")
            buildList {
                while (cursor.moveToNext()) {
                    add(TriggerRules.match(cursor.getString(body), settings.activatePhrases, settings.clearPhrases))
                }
            }
        }.orEmpty()

    /**
     * A Samsung turns USB debugging off until the first unlock, so nothing can be asked of it over
     * adb in that state, and the phone has to report by itself. This calls only a probe URL set by
     * a test, never a trigger URL, and a release build does not contain it.
     */
    private fun bootProbe(container: AppContainer, context: Context) {
        val probe = probePreferences(context).getString("url", null)?.takeIf { it.isNotBlank() } ?: return
        container.appScope.launch {
            repeat(PROBE_ATTEMPTS) { attempt ->
                val settings = container.configuration.current().triggerSettings()
                val matches = runCatching { storedMatches(context, settings) }.getOrDefault(emptyList())
                val urls = TriggerAction.entries.count { container.configuration.triggerUrl(it) != null }
                val report = "attempt=$attempt&unlocked=${DeviceStorage.isUserUnlocked(context)}&armed=${settings.armed}" +
                    "&rows=${matches.size}&threats=${matches.count { it == TriggerMatch.ACTIVATE }}" +
                    "&clears=${matches.count { it == TriggerMatch.CLEAR }}&urls=$urls" +
                    "&last=${container.repository.latestCommand()?.action}"
                val status = runCatching { container.transport.call(TriggerMethod.GET, "$probe?$report") }.getOrNull()
                Log.i(TAG, "boot_probe $report status=$status")
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    /**
     * Sends a real alert stored on the phone through the real rules, queue and URL call. A retail
     * phone cannot be sent an alert on demand, so this is the closest test. It is a manual command,
     * which never counts as evidence of a live alert, and a release build does not contain it.
     */
    private fun replay(container: AppContainer, context: Context, intent: Intent) {
        val wanted = if (intent.getStringExtra("kind") == "clear") TriggerMatch.CLEAR else TriggerMatch.ACTIVATE
        val settings = container.configuration.current().triggerSettings()
        val matches = storedMatches(context, settings)
        Log.i(TAG, "replay stored_alerts=${matches.size} rules=${matches.groupingBy { it }.eachCount()} wanted=$wanted")
        if (wanted !in matches) return
        val action = if (wanted == TriggerMatch.CLEAR) TriggerAction.CLEAR else TriggerAction.ACTIVATE
        container.appScope.launch { container.trigger.manual(action) }
    }

    private fun provision(container: AppContainer, context: Context, intent: Intent) {
        val problems = container.updateConfig { config ->
            config.copy(
                serverHost = intent.getStringExtra("host") ?: config.serverHost,
                serverPort = intent.getIntExtra("port", config.serverPort),
                useTls = intent.getBooleanExtra("tls", config.useTls),
                deviceId = intent.getStringExtra("device_id") ?: config.deviceId,
                retryBaseSeconds = intent.getIntExtra("retry_base", config.retryBaseSeconds),
                retryMaxSeconds = intent.getIntExtra("retry_max", config.retryMaxSeconds),
                heartbeatSeconds = intent.getIntExtra("heartbeat", config.heartbeatSeconds),
                duplicateWindowMinutes = intent.getIntExtra("duplicate_window", config.duplicateWindowMinutes),
                cellBroadcastDuplicateWindowHours = intent.getIntExtra("ui_window_hours", config.cellBroadcastDuplicateWindowHours),
                pinnedCaPem = intent.getStringExtra("pinned_ca_b64")?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: config.pinnedCaPem,
                triggerArmed = intent.getBooleanExtra("armed", config.triggerArmed),
                allowLabTriggers = intent.getBooleanExtra("allow_lab_triggers", config.allowLabTriggers),
                triggerMaxAgeMinutes = intent.getIntExtra("trigger_max_age", config.triggerMaxAgeMinutes),
                scheduleEnabled = intent.getBooleanExtra("schedule_enabled", config.scheduleEnabled),
                scheduleDays = intent.getStringExtra("schedule_days")?.split(',')?.map(String::toInt)?.toSet() ?: config.scheduleDays,
                scheduleStartMinute = intent.getIntExtra("schedule_start", config.scheduleStartMinute),
                scheduleEndMinute = intent.getIntExtra("schedule_end", config.scheduleEndMinute),
            )
        }
        intent.getStringExtra("boot_probe_url")?.let { probePreferences(context).edit().putString("url", it).commit() }
        intent.getStringExtra("activate_url")?.let { container.configuration.setTriggerUrl(TriggerAction.ACTIVATE, it) }
        intent.getStringExtra("clear_url")?.let { container.configuration.setTriggerUrl(TriggerAction.CLEAR, it) }
        intent.getStringExtra("token")?.let(container.configuration::setAuthToken)
        intent.getStringExtra("test_pin")?.let(container.appLock::setPin)
        Log.i(TAG, "provisioned problems=$problems")
        container.delivery.kick()
    }

    private fun simulate(container: AppContainer, intent: Intent) {
        val language = if (intent.getStringExtra("lang") == "en") {
            SimulationManager.Language.ENGLISH
        } else {
            SimulationManager.Language.ARABIC
        }
        when (intent.getStringExtra("test")) {
            "a" -> container.simulation.postSimulatedNotification(language)
            "b" -> container.simulation.launchSimulatedPopup(language)
            "c" -> container.appScope.launch { container.simulation.sendSimulatedServerAlert(language) }
        }
    }

    private companion object {
        const val TAG = "CellAlertHookDebug"
        const val PROBE_ATTEMPTS = 30
        const val PROBE_INTERVAL_MS = 10_000L
        const val ACTION_PROVISION = "app.cellalerthook.debug.PROVISION"
        const val ACTION_SIMULATE = "app.cellalerthook.debug.SIMULATE"
        const val ACTION_REPLAY = "app.cellalerthook.debug.REPLAY"
        const val ACTION_CHECK_URL = "app.cellalerthook.debug.CHECK_URL"
    }
}
