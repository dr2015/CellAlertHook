package app.cellalerthook.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cellalerthook.AppContainer
import app.cellalerthook.R
import app.cellalerthook.data.db.AlertEvent
import app.cellalerthook.data.db.TriggerCommand
import app.cellalerthook.domain.AlertClass
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.DeliveryStatus
import app.cellalerthook.domain.VerificationStatus
import app.cellalerthook.ui.AlertMessageText
import app.cellalerthook.ui.Badge
import app.cellalerthook.ui.KeyValueRow
import app.cellalerthook.ui.SectionCard
import app.cellalerthook.ui.StatusLevel
import app.cellalerthook.ui.formatTime
import app.cellalerthook.ui.triggerSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AlertsViewModel(container: AppContainer) : ViewModel() {
    val events: StateFlow<List<AlertEvent>> = container.repository.observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val commands: StateFlow<Map<String, TriggerCommand>> = container.repository.observeCommands()
        .map { list -> list.associateBy { it.eventId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AlertsViewModel(container) }
        }
    }
}

@Composable
fun AlertsScreen(container: AppContainer) {
    val model: AlertsViewModel = viewModel(factory = AlertsViewModel.factory(container))
    val events by model.events.collectAsStateWithLifecycle()
    val commands by model.commands.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.alerts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(events, key = { it.eventId }) { event ->
            AlertCard(
                event = event,
                command = commands[event.eventId],
                showDelivery = container.configuration.current().isServerConfigured,
                expanded = expanded == event.eventId,
                onToggle = { expanded = if (expanded == event.eventId) null else event.eventId },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertCard(
    event: AlertEvent,
    command: TriggerCommand?,
    showDelivery: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SectionCard(
        title = null,
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OriginBadge(event)
            if (event.alertClass.testOrExercise == true) {
                Badge(stringResource(R.string.alert_badge_drill), StatusLevel.WARN)
            }
            if (showDelivery) DeliveryBadge(event.deliveryStatus)
            if (command != null) Badge(triggerSummary(command), StatusLevel.NEUTRAL)
        }
        event.title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium.merge(TextStyle(textDirection = TextDirection.Content)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AlertMessageText(event.message, Modifier.fillMaxWidth(), maxLines = if (expanded) Int.MAX_VALUE else 3)
        Text(
            formatTime(event.detectedAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KeyValueRow(
                stringResource(R.string.alert_trigger_action),
                command?.let { triggerSummary(it) } ?: stringResource(R.string.alert_trigger_none),
            )
            AlertDetails(event)
        }
    }
}

@Composable
private fun OriginBadge(event: AlertEvent) {
    when {
        event.simulated -> Badge(stringResource(R.string.alert_badge_simulated), StatusLevel.WARN)
        event.verification == VerificationStatus.CELL_BROADCAST_STACK -> Badge(stringResource(R.string.alert_badge_live), StatusLevel.OK)
        event.verification == VerificationStatus.CELL_BROADCAST_STACK_LAB -> Badge(stringResource(R.string.alert_badge_lab), StatusLevel.NEUTRAL)
        else -> Badge(event.verification.wireName, StatusLevel.NEUTRAL)
    }
}

@Composable
private fun DeliveryBadge(status: DeliveryStatus) {
    val (text, level) = when (status) {
        DeliveryStatus.PENDING -> R.string.delivery_pending to StatusLevel.WARN
        DeliveryStatus.DELIVERED -> R.string.delivery_delivered to StatusLevel.OK
        DeliveryStatus.REJECTED -> R.string.delivery_rejected to StatusLevel.BAD
        DeliveryStatus.BLOCKED -> R.string.delivery_blocked to StatusLevel.BAD
    }
    Badge(stringResource(text), level)
}

@Composable
private fun AlertDetails(event: AlertEvent) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        KeyValueRow(stringResource(R.string.alert_detected_at), formatTime(event.detectedAtMs))
        event.receivedAtMs?.let { KeyValueRow(stringResource(R.string.alert_received_at), formatTime(it)) }
        KeyValueRow(stringResource(R.string.alert_source), event.source.wireName, technical = true)
        KeyValueRow(stringResource(R.string.alert_capture_method), event.captureMethod.wireName, technical = true)
        KeyValueRow(stringResource(R.string.alert_validation), event.verification.wireName, technical = true)
        KeyValueRow(stringResource(R.string.alert_confidence), event.confidence.wireName, technical = true)
        if (event.alertClass != AlertClass.UNKNOWN) {
            KeyValueRow(stringResource(R.string.alert_class), event.alertClass.wireName, technical = true)
        }
        KeyValueRow(stringResource(R.string.alert_severity), event.severity.wireName, technical = true)
        KeyValueRow(stringResource(R.string.alert_language), event.language, technical = true)
        event.cellBroadcast?.let {
            KeyValueRow(stringResource(R.string.alert_message_id), it.messageId.toString(), technical = true)
            KeyValueRow(stringResource(R.string.alert_serial), "0x%04X".format(it.serialNumber), technical = true)
            it.plmn?.let { plmn -> KeyValueRow(stringResource(R.string.alert_plmn), plmn, technical = true) }
        }
        event.originPackage?.let { KeyValueRow(stringResource(R.string.alert_origin), it, technical = true) }
        KeyValueRow(stringResource(R.string.alert_attempts), event.deliveryAttempts.toString(), technical = true)
        KeyValueRow(stringResource(R.string.alert_duplicates), event.duplicateCount.toString(), technical = true)
        if (event.corroboratingMethods.isNotEmpty()) {
            KeyValueRow(
                stringResource(R.string.alert_corroborated),
                event.corroboratingMethods.joinToString(", ", transform = CaptureMethod::wireName),
                technical = true,
            )
        }
        event.lastDeliveryError?.let { KeyValueRow(stringResource(R.string.alert_last_error), it, technical = true) }
        KeyValueRow(stringResource(R.string.alert_event_id), event.eventId, technical = true)
    }
}
