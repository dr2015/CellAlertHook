package app.cellalerthook.ui.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cellalerthook.AppContainer
import app.cellalerthook.R
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.data.db.TriggerCommand
import app.cellalerthook.data.db.TriggerStatus
import app.cellalerthook.detection.DetectorState
import app.cellalerthook.detection.DetectorStatus
import app.cellalerthook.domain.BuildProvenance
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.TriggerSchedule
import app.cellalerthook.service.CloseProtectionReceiver
import app.cellalerthook.service.HealthSnapshot
import app.cellalerthook.service.ServerState
import app.cellalerthook.ui.KeyValueRow
import app.cellalerthook.ui.SectionCard
import app.cellalerthook.ui.StatusLevel
import app.cellalerthook.ui.StatusRow
import app.cellalerthook.ui.formatTime
import app.cellalerthook.ui.statusColor
import app.cellalerthook.ui.triggerSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

enum class Readiness { READY, DEGRADED_UI_ONLY, DEGRADED_NOT_ARMED, DEGRADED_TRIGGER, DEGRADED_SERVER, NOT_READY }

data class DashboardState(
    val health: HealthSnapshot = HealthSnapshot(),
    val pending: Int = 0,
    val lastDetectionMs: Long? = null,
    val lastDeliveryMs: Long? = null,
    val realAlertReceived: Boolean = false,
    val deviceId: String = "",
    val armed: Boolean = false,
    val serverConfigured: Boolean = false,
    val lastCommand: TriggerCommand? = null,
    val schedule: TriggerSchedule? = null,
) {
    val readiness: Readiness
        get() {
            val active = health.detectors.filter { it.state == DetectorState.ACTIVE }
            return when {
                active.isEmpty() -> Readiness.NOT_READY
                active.none { it.id == PROVIDER_ID } -> Readiness.DEGRADED_UI_ONLY
                !armed -> Readiness.DEGRADED_NOT_ARMED
                lastCommand?.status == TriggerStatus.PENDING && lastCommand.attempts > 0 -> Readiness.DEGRADED_TRIGGER
                serverConfigured && health.server.state != ServerState.REACHABLE -> Readiness.DEGRADED_SERVER
                else -> Readiness.READY
            }
        }

    companion object {
        const val PROVIDER_ID = "cell_broadcast_provider"
    }
}

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    val provenance: BuildProvenance = container.provenance

    val state: StateFlow<DashboardState> = combine(
        container.health.snapshot,
        container.repository.observePendingCount(),
        container.repository.observeLastRealDetection(),
        container.repository.observeLastDelivery(),
        combine(
            container.repository.observeEvidence(),
            container.configuration.config,
            container.repository.observeCommands(limit = 1),
        ) { evidence, config, commands ->
            val real = evidence.any {
                it.kind == EvidenceKind.REAL_GOVERNMENT_ALERT && it.provenance == EvidenceProvenance.LIVE_DEVICE
            }
            DashboardState(
                realAlertReceived = real,
                deviceId = config.deviceId,
                armed = config.triggerArmed,
                serverConfigured = config.isServerConfigured,
                lastCommand = commands.firstOrNull(),
                schedule = config.triggerSettings().schedule,
            )
        },
    ) { health, pending, lastDetection, lastDelivery, settled ->
        settled.copy(health = health, pending = pending, lastDetectionMs = lastDetection, lastDeliveryMs = lastDelivery)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun setPa(action: TriggerAction) {
        container.appScope.launch { container.trigger.manual(action) }
    }

    fun refresh() {
        container.health.refresh()
    }

    suspend fun refreshDetectors() {
        container.registry.refreshAll()
        container.health.refresh()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DashboardViewModel(container) }
        }
    }
}

@Composable
fun DashboardScreen(container: AppContainer) {
    val model: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            model.refreshDetectors()
            delay(5_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReadinessBanner(state.readiness)
        SetupCard(state, model::refresh)
        TriggerCard(state, model::setPa)
        DetectionCard(state)
        ConnectivityCard(state)
        ActivityCard(state)
        RealWorldCard(state.realAlertReceived, model.provenance.isProductionBuild)
        DeviceCard(state.deviceId)
    }
}

@Composable
private fun ReadinessBanner(readiness: Readiness) {
    val (title, detail, level) = when (readiness) {
        Readiness.READY -> Triple(R.string.readiness_ready, R.string.readiness_ready_detail, StatusLevel.OK)
        Readiness.DEGRADED_UI_ONLY -> Triple(R.string.readiness_degraded, R.string.readiness_degraded_ui_only, StatusLevel.WARN)
        Readiness.DEGRADED_NOT_ARMED -> Triple(R.string.readiness_degraded, R.string.readiness_degraded_not_armed, StatusLevel.WARN)
        Readiness.DEGRADED_TRIGGER -> Triple(R.string.readiness_degraded, R.string.readiness_degraded_trigger, StatusLevel.BAD)
        Readiness.DEGRADED_SERVER -> Triple(R.string.readiness_degraded, R.string.readiness_degraded_server, StatusLevel.WARN)
        Readiness.NOT_READY -> Triple(R.string.readiness_not_ready, R.string.readiness_not_ready_detail, StatusLevel.BAD)
    }
    val color = statusColor(level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (level == StatusLevel.OK) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = color)
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun SetupCard(state: DashboardState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val health = state.health
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onChanged()
    }
    val grantCommand = "adb shell pm grant ${context.packageName} android.permission.READ_CELL_BROADCASTS"

    val needsAnything = !health.cellBroadcastPermissionGranted || !health.accessibilityEnabled ||
        !health.notificationAccessGranted || !health.batteryOptimizationIgnored ||
        !health.postNotificationsGranted || !health.closeProtectionActive || !state.armed
    if (!needsAnything) return

    SectionCard(stringResource(R.string.section_setup)) {
        if (!health.cellBroadcastPermissionGranted) {
            SetupItem(R.string.setup_cb_title, R.string.setup_cb_body, R.string.setup_copy_command) {
                clipboard.setText(AnnotatedString(grantCommand))
            }
        }
        if (!health.accessibilityEnabled) {
            SetupItem(R.string.setup_accessibility_title, R.string.setup_accessibility_body, R.string.action_open) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        if (!health.notificationAccessGranted) {
            SetupItem(R.string.setup_notification_title, R.string.setup_notification_body, R.string.action_open) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        if (!health.batteryOptimizationIgnored) {
            SetupItem(R.string.setup_battery_title, R.string.setup_battery_body, R.string.action_allow) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }
        }
        if (!health.postNotificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SetupItem(R.string.setup_post_notifications_title, R.string.setup_post_notifications_body, R.string.action_allow) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!health.closeProtectionActive) {
            SetupItem(R.string.setup_protection_title, R.string.setup_protection_body, R.string.action_allow) {
                context.startActivity(CloseProtectionReceiver.enableIntent(context))
            }
        }
        if (!state.armed) {
            SetupItem(R.string.setup_trigger_title, R.string.setup_trigger_body, null) {}
        }
    }
}

@Composable
private fun scheduleSummary(schedule: TriggerSchedule?): String {
    if (schedule == null || !schedule.enabled) return stringResource(R.string.trigger_hours_always)
    val days = DayOfWeek.entries.filter { it in schedule.days }
        .sortedBy { it.value % 7 }
        .joinToString(" ") { it.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()) }
    return "$days ${TriggerSchedule.formatMinutes(schedule.startMinute)}-${TriggerSchedule.formatMinutes(schedule.endMinute)}"
}

/** The manual buttons exist to test the URLs and to stop the target if an all-clear never comes. */
@Composable
private fun TriggerCard(state: DashboardState, onSet: (TriggerAction) -> Unit) {
    var confirming by remember { mutableStateOf<TriggerAction?>(null) }
    SectionCard(stringResource(R.string.section_trigger)) {
        StatusRow(
            stringResource(R.string.label_trigger_trigger),
            stringResource(if (state.armed) R.string.status_armed else R.string.status_not_armed),
            if (state.armed) StatusLevel.OK else StatusLevel.WARN,
        )
        KeyValueRow(
            stringResource(R.string.label_trigger_last_command),
            state.lastCommand?.let { triggerSummary(it) } ?: stringResource(R.string.status_none_yet),
        )
        state.lastCommand?.let { KeyValueRow(stringResource(R.string.alert_detected_at), formatTime(it.createdAtMs)) }
        state.lastCommand?.lastError?.let { KeyValueRow(stringResource(R.string.alert_last_error), it, technical = true) }
        KeyValueRow(stringResource(R.string.label_trigger_hours), scheduleSummary(state.schedule))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirming = TriggerAction.ACTIVATE }) { Text(stringResource(R.string.trigger_turn_on)) }
            OutlinedButton(onClick = { confirming = TriggerAction.CLEAR }) { Text(stringResource(R.string.trigger_turn_off)) }
        }
    }
    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = {
                Text(stringResource(if (action == TriggerAction.ACTIVATE) R.string.trigger_confirm_on else R.string.trigger_confirm_off))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onSet(action)
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SetupItem(title: Int, body: Int, action: Int?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.let { TextButton(onClick = onAction) { Text(stringResource(it)) } }
    }
}

@Composable
private fun DetectionCard(state: DashboardState) {
    val health = state.health
    SectionCard(stringResource(R.string.section_detection)) {
        StatusRow(
            stringResource(R.string.label_receiver_service),
            stringResource(if (health.serviceRunning) R.string.status_running else R.string.status_stopped),
            if (health.serviceRunning) StatusLevel.OK else StatusLevel.BAD,
        )
        detectorRow(health.detectors, DashboardState.PROVIDER_ID, R.string.label_cell_broadcast)
        detectorRow(health.detectors, "accessibility", R.string.label_accessibility)
        detectorRow(health.detectors, "notification", R.string.label_notification)
    }
}

@Composable
private fun detectorRow(detectors: List<DetectorStatus>, id: String, label: Int) {
    val status = detectors.firstOrNull { it.id == id }
    val (value, level) = when (status?.state) {
        DetectorState.ACTIVE -> R.string.status_active to StatusLevel.OK
        DetectorState.BLOCKED -> R.string.status_blocked to StatusLevel.WARN
        DetectorState.DISABLED -> R.string.status_disabled to StatusLevel.NEUTRAL
        DetectorState.UNAVAILABLE -> R.string.status_unavailable to StatusLevel.BAD
        null -> R.string.status_unknown to StatusLevel.NEUTRAL
    }
    StatusRow(stringResource(label), stringResource(value), level)
}

@Composable
private fun ConnectivityCard(state: DashboardState) {
    val network = state.health.network
    val (networkText, networkLevel) = when {
        !network.wifiConnected -> R.string.network_none to StatusLevel.BAD
        network.wifiHasValidatedInternet -> R.string.network_wifi to StatusLevel.OK
        else -> R.string.network_wifi_no_internet to StatusLevel.OK
    }
    val (serverText, serverLevel) = when (state.health.server.state) {
        ServerState.REACHABLE -> R.string.status_reachable to StatusLevel.OK
        ServerState.AUTH_FAILED -> R.string.status_auth_failed to StatusLevel.BAD
        ServerState.UNREACHABLE -> R.string.status_unreachable to StatusLevel.BAD
        ServerState.ERROR -> R.string.status_error to StatusLevel.BAD
        ServerState.NOT_CONFIGURED -> R.string.status_not_configured to StatusLevel.WARN
        ServerState.UNKNOWN -> R.string.status_unknown to StatusLevel.NEUTRAL
    }
    SectionCard(stringResource(R.string.section_connectivity)) {
        StatusRow(stringResource(R.string.label_network), stringResource(networkText), networkLevel)
        if (state.serverConfigured) StatusRow(stringResource(R.string.label_server), stringResource(serverText), serverLevel)
    }
}

@Composable
private fun ActivityCard(state: DashboardState) {
    val none = stringResource(R.string.status_none_yet)
    SectionCard(stringResource(R.string.section_activity)) {
        KeyValueRow(stringResource(R.string.label_last_detected), state.lastDetectionMs?.let(::formatTime) ?: none)
        if (state.serverConfigured) {
            KeyValueRow(stringResource(R.string.label_last_transmitted), state.lastDeliveryMs?.let(::formatTime) ?: none)
            KeyValueRow(stringResource(R.string.label_pending), state.pending.toString())
        }
    }
}

/** Stays in view until a real broadcast has been seen, so a simulated pass cannot be mistaken for proof. */
@Composable
private fun RealWorldCard(realAlertReceived: Boolean, productionBuild: Boolean) {
    SectionCard(stringResource(R.string.section_real_world)) {
        val (text, level) = when {
            realAlertReceived -> R.string.proof_real_alert_seen to StatusLevel.OK
            !productionBuild -> R.string.proof_lab_build to StatusLevel.WARN
            else -> R.string.proof_real_alert_missing to StatusLevel.WARN
        }
        Box {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = statusColor(level))
        }
    }
}

@Composable
private fun DeviceCard(deviceId: String) {
    SectionCard(stringResource(R.string.section_device)) {
        KeyValueRow(stringResource(R.string.label_device_id), deviceId, technical = true)
        KeyValueRow(stringResource(R.string.label_device_model), "${Build.MANUFACTURER} ${Build.MODEL}", technical = true)
        KeyValueRow(stringResource(R.string.label_android_version), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", technical = true)
    }
}
