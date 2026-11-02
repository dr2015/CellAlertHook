package app.cellalerthook.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.cellalerthook.BuildConfig
import app.cellalerthook.config.DeviceConfiguration
import app.cellalerthook.data.AlertRepository
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.delivery.HeartbeatPayload
import app.cellalerthook.delivery.OkHttpAlertTransport
import app.cellalerthook.delivery.TransportRequest
import app.cellalerthook.detection.CellBroadcastProviderDetector
import app.cellalerthook.detection.DetectorRegistry
import app.cellalerthook.detection.DetectorStatus
import app.cellalerthook.detection.AlertAccessibilityService
import app.cellalerthook.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeFormatter

enum class ServerState { NOT_CONFIGURED, UNKNOWN, REACHABLE, AUTH_FAILED, UNREACHABLE, ERROR }

data class ServerStatus(
    val state: ServerState = ServerState.UNKNOWN,
    val checkedAtMs: Long? = null,
    val latencyMs: Long? = null,
    val detail: String? = null,
)

data class HealthSnapshot(
    val serviceRunning: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val cellBroadcastPermissionGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val closeProtectionActive: Boolean = false,
    val detectors: List<DetectorStatus> = emptyList(),
    val server: ServerStatus = ServerStatus(),
    val network: NetworkState = NetworkState(),
    val checkedAtMs: Long = 0,
)

/** Answers one question for the operator: would this phone catch an alert right now? */
class HealthMonitor(
    private val context: Context,
    private val configuration: DeviceConfiguration,
    private val registry: DetectorRegistry,
    private val repository: AlertRepository,
    private val network: NetworkMonitor,
    private val transport: OkHttpAlertTransport,
    private val log: DiagnosticsLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val snapshotFlow = MutableStateFlow(HealthSnapshot())
    private var lastServerCheckMs = 0L
    private var lastHeartbeatMs = 0L

    @Volatile
    var serviceRunning = false

    val snapshot: StateFlow<HealthSnapshot> = snapshotFlow.asStateFlow()

    fun refresh() {
        snapshotFlow.value = snapshotFlow.value.copy(
            serviceRunning = serviceRunning,
            notificationAccessGranted = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
            accessibilityEnabled = isAccessibilityEnabled(),
            cellBroadcastPermissionGranted = isGranted(CellBroadcastProviderDetector.PERMISSION),
            postNotificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                isGranted(Manifest.permission.POST_NOTIFICATIONS),
            batteryOptimizationIgnored = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            closeProtectionActive = CloseProtectionReceiver.isActive(context),
            detectors = registry.detectors.map { it.status.value },
            network = network.state.value,
            checkedAtMs = clock(),
        )
    }

    /** Runs on the service tick. Each piece is rate limited by its own interval. */
    suspend fun tick() {
        refresh()
        val now = clock()
        if (now - lastServerCheckMs >= SERVER_CHECK_INTERVAL_MS) {
            lastServerCheckMs = now
            checkServer()
        }
        val heartbeatInterval = configuration.current().heartbeatSeconds * 1000L
        if (now - lastHeartbeatMs >= heartbeatInterval) {
            lastHeartbeatMs = now
            sendHeartbeat()
        }
    }

    suspend fun checkServer(): ServerStatus {
        val config = configuration.current()
        if (!config.isServerConfigured) {
            return ServerStatus(ServerState.NOT_CONFIGURED).also(::publish)
        }
        val started = clock()
        val status = try {
            val response = transport.get(config.url(config.healthPath), authHeaders())
            val state = when (response.code) {
                in 200..299 -> ServerState.REACHABLE
                401, 403 -> ServerState.AUTH_FAILED
                else -> ServerState.ERROR
            }
            ServerStatus(state, clock(), clock() - started, "http_${response.code}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerStatus(ServerState.UNREACHABLE, clock(), null, e.javaClass.simpleName)
        }
        val previous = snapshotFlow.value.server.state
        if (status.state != previous) {
            log.log(LogLevel.INFO, CATEGORY, "server_state=${status.state} detail=${status.detail}")
        }
        publish(status)
        return status
    }

    private suspend fun sendHeartbeat() {
        val config = configuration.current()
        if (!config.isServerConfigured) return
        val payload = HeartbeatPayload(
            deviceId = config.deviceId,
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(clock())),
            appVersion = BuildConfig.VERSION_NAME,
            androidSdk = Build.VERSION.SDK_INT,
            pendingEvents = repository.observePendingCount().first(),
            detectors = registry.detectors.associate { it.id to it.status.value.state.name },
        )
        val headers = authHeaders() + ("Content-Type" to "application/json; charset=utf-8")
        try {
            transport.post(TransportRequest(config.url(config.heartbeatPath), payload.toJson(), headers))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(LogLevel.DEBUG, CATEGORY, "heartbeat_failed ${e.javaClass.simpleName}")
        }
    }

    private fun authHeaders(): Map<String, String> = buildMap {
        put("X-CellAlertHook-Device-Id", configuration.current().deviceId)
        configuration.authToken()?.let { put("Authorization", "Bearer $it") }
    }

    private fun publish(status: ServerStatus) {
        snapshotFlow.value = snapshotFlow.value.copy(server = status)
    }

    private fun isGranted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(context, AlertAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private companion object {
        const val CATEGORY = "health"
        const val SERVER_CHECK_INTERVAL_MS = 60_000L
    }
}
