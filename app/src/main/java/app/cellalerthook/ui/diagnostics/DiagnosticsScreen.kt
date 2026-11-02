package app.cellalerthook.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cellalerthook.AppContainer
import app.cellalerthook.R
import app.cellalerthook.data.db.DeliveryAttempt
import app.cellalerthook.data.db.DiagnosticEntry
import app.cellalerthook.data.db.EvidenceKind
import app.cellalerthook.data.db.EvidenceProvenance
import app.cellalerthook.data.db.EvidenceRecord
import app.cellalerthook.detection.CapabilityReport
import app.cellalerthook.detection.CapabilityVerdict
import app.cellalerthook.detection.PackageProbe
import app.cellalerthook.diagnostics.ConnectionTestStep
import app.cellalerthook.diagnostics.DiagnosticsReport
import app.cellalerthook.diagnostics.TestStepResult
import app.cellalerthook.simulation.SimulationManager
import app.cellalerthook.ui.KeyValueRow
import app.cellalerthook.ui.SectionCard
import app.cellalerthook.ui.StatusLevel
import app.cellalerthook.ui.TechnicalText
import app.cellalerthook.ui.formatTime
import app.cellalerthook.ui.statusColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    private val capabilityFlow = MutableStateFlow<CapabilityReport?>(null)
    private val stepsFlow = MutableStateFlow<List<ConnectionTestStep>>(emptyList())
    private val runningFlow = MutableStateFlow(false)

    val capability: StateFlow<CapabilityReport?> = capabilityFlow.asStateFlow()
    val steps: StateFlow<List<ConnectionTestStep>> = stepsFlow.asStateFlow()
    val running: StateFlow<Boolean> = runningFlow.asStateFlow()

    val evidence = container.repository.observeEvidence()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accessibilityEvents = container.diagnostics.observeCategory("accessibility")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notificationEvents = container.diagnostics.observeCategory("notification")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val attempts = container.repository.observeAttempts(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val log = container.diagnostics.observe(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun packages(): List<PackageProbe> =
        container.packages.effectiveAllowlist(container.configuration.current().packageAllowlist)
            .sorted()
            .map(container.packages::probe)

    fun refreshCapability() {
        viewModelScope.launch { capabilityFlow.value = container.capabilities.check() }
    }

    fun runConnectionTest() {
        viewModelScope.launch {
            runningFlow.value = true
            stepsFlow.value = container.connectionTester.run()
            runningFlow.value = false
        }
    }

    fun simulateNotification(language: SimulationManager.Language) = container.simulation.postSimulatedNotification(language)

    fun simulatePopup(language: SimulationManager.Language) = container.simulation.launchSimulatedPopup(language)

    fun simulateServerAlert(language: SimulationManager.Language) {
        viewModelScope.launch { container.simulation.sendSimulatedServerAlert(language) }
    }

    suspend fun report(): String = DiagnosticsReport.build(
        capability = container.capabilities.check(),
        evidence = container.repository.observeEvidence().first(),
        health = container.health.snapshot.value,
        log = container.diagnostics.recent(100),
    )

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DiagnosticsViewModel(container) }
        }
    }
}

@Composable
fun DiagnosticsScreen(container: AppContainer) {
    val model: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.factory(container))
    val capability by model.capability.collectAsStateWithLifecycle()
    val evidence by model.evidence.collectAsStateWithLifecycle()
    val steps by model.steps.collectAsStateWithLifecycle()
    val running by model.running.collectAsStateWithLifecycle()
    val accessibilityEvents by model.accessibilityEvents.collectAsStateWithLifecycle()
    val notificationEvents by model.notificationEvents.collectAsStateWithLifecycle()
    val attempts by model.attempts.collectAsStateWithLifecycle()
    val log by model.log.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { model.refreshCapability() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        capability?.let { CapabilityCard(it, model::refreshCapability) }
        EvidenceCard(evidence)
        TestToolsCard(model)
        ConnectionCard(steps, running, model::runConnectionTest)
        PackagesCard(model.packages())
        EntryListCard(stringResource(R.string.diag_a11y_events), accessibilityEvents)
        EntryListCard(stringResource(R.string.diag_notification_events), notificationEvents)
        AttemptsCard(attempts)
        EntryListCard(stringResource(R.string.diag_errors), log)
        OutlinedButton(
            onClick = {
                scope.launch {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, model.report())
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Text(stringResource(R.string.diag_share))
        }
    }
}

@Composable
private fun CapabilityCard(report: CapabilityReport, onRefresh: () -> Unit) {
    val (verdict, level) = when (report.verdict) {
        CapabilityVerdict.DIRECT_READ_AVAILABLE -> R.string.diag_verdict_available to StatusLevel.OK
        CapabilityVerdict.DIRECT_READ_NEEDS_GRANT -> R.string.diag_verdict_needs_grant to StatusLevel.WARN
        CapabilityVerdict.DIRECT_READ_NOT_AVAILABLE -> R.string.diag_verdict_not_available to StatusLevel.BAD
    }
    SectionCard(stringResource(R.string.diag_capability)) {
        Text(stringResource(verdict), style = MaterialTheme.typography.bodyMedium, color = statusColor(level))
        KeyValueRow("Android", "${report.release} / API ${report.sdkInt} / patch ${report.securityPatch}", technical = true)
        KeyValueRow("Device", "${report.manufacturer} ${report.model}", technical = true)
        KeyValueRow("Build", "${report.buildType} ${report.buildTags}", technical = true)
        KeyValueRow("SIM / network", "${report.simOperator.ifEmpty { "-" }} / ${report.networkOperator.ifEmpty { "-" }}", technical = true)
        KeyValueRow("Read access", "READ_CELL_BROADCASTS\n${report.readPermission.protection}, granted=${report.readPermission.granted}", technical = true)
        KeyValueRow("Direct broadcast", "RECEIVE_EMERGENCY_BROADCAST\n${report.emergencyPermission.protection}, granted=${report.emergencyPermission.granted}", technical = true)
        KeyValueRow("Provider", report.providerPackage ?: "not present", technical = true)
        KeyValueRow("/history", "${report.historyQuery.outcome} rows=${report.historyQuery.rows ?: "-"}", technical = true)
        KeyValueRow("Full table", report.fullTableQuery.outcome.name, technical = true)
        KeyValueRow("Protected broadcasts seen", report.protectedBroadcastsReceived.toString(), technical = true)
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.diag_refresh)) }
    }
}

@Composable
private fun EvidenceCard(records: List<EvidenceRecord>) {
    val rows = listOf(
        EvidenceKind.DIRECT_CELL_BROADCAST_CAPTURE to R.string.evidence_direct,
        EvidenceKind.ACCESSIBILITY_POPUP_CAPTURE to R.string.evidence_accessibility,
        EvidenceKind.NOTIFICATION_CAPTURE to R.string.evidence_notification,
        EvidenceKind.SERVER_DELIVERY to R.string.evidence_delivery,
        EvidenceKind.REAL_GOVERNMENT_ALERT to R.string.evidence_real,
    )
    val columns = listOf(
        EvidenceProvenance.SIMULATED to R.string.diag_col_simulated,
        EvidenceProvenance.LAB_BUILD to R.string.diag_col_lab,
        EvidenceProvenance.LIVE_DEVICE to R.string.diag_col_live,
    )
    SectionCard(stringResource(R.string.diag_evidence)) {
        Text(
            stringResource(R.string.diag_evidence_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("", Modifier.weight(2.2f))
            columns.forEach { (_, label) ->
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        rows.forEach { (kind, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2.2f))
                columns.forEach { (provenance, _) ->
                    val record = records.firstOrNull { it.kind == kind && it.provenance == provenance }
                    Text(
                        record?.count?.toString() ?: "–",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = when {
                            record == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            provenance == EvidenceProvenance.LIVE_DEVICE -> statusColor(StatusLevel.OK)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestToolsCard(model: DiagnosticsViewModel) {
    var language by rememberSaveable { mutableStateOf(SimulationManager.Language.ARABIC) }
    SectionCard(stringResource(R.string.diag_tests)) {
        Text(
            stringResource(R.string.diag_tests_hint),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(StatusLevel.WARN),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = language == SimulationManager.Language.ARABIC,
                onClick = { language = SimulationManager.Language.ARABIC },
                label = { Text(stringResource(R.string.diag_language_ar)) },
            )
            FilterChip(
                selected = language == SimulationManager.Language.ENGLISH,
                onClick = { language = SimulationManager.Language.ENGLISH },
                label = { Text(stringResource(R.string.diag_language_en)) },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = { model.simulateNotification(language) }) { Text(stringResource(R.string.diag_test_a)) }
            OutlinedButton(onClick = { model.simulatePopup(language) }) { Text(stringResource(R.string.diag_test_b)) }
            OutlinedButton(onClick = { model.simulateServerAlert(language) }) { Text(stringResource(R.string.diag_test_c)) }
        }
    }
}

@Composable
private fun ConnectionCard(steps: List<ConnectionTestStep>, running: Boolean, onRun: () -> Unit) {
    SectionCard(stringResource(R.string.diag_network)) {
        Button(onClick = onRun, enabled = !running) {
            Text(stringResource(if (running) R.string.diag_running else R.string.diag_run_test))
        }
        steps.forEach { step ->
            val level = when (step.result) {
                TestStepResult.PASS -> StatusLevel.OK
                TestStepResult.FAIL -> StatusLevel.BAD
                TestStepResult.SKIPPED -> StatusLevel.NEUTRAL
            }
            TechnicalText("${step.result} ${step.name} (${step.durationMs} ms)\n${step.detail}", color = statusColor(level))
        }
    }
}

@Composable
private fun PackagesCard(packages: List<PackageProbe>) {
    SectionCard(stringResource(R.string.diag_packages)) {
        packages.forEach { probe ->
            val state = when {
                !probe.installed -> stringResource(R.string.diag_package_missing)
                probe.system -> stringResource(R.string.diag_package_system)
                else -> stringResource(R.string.diag_package_user)
            }
            TechnicalText(probe.packageName)
            Text(
                listOfNotNull(state, probe.versionName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttemptsCard(attempts: List<DeliveryAttempt>) {
    SectionCard(stringResource(R.string.diag_attempts)) {
        if (attempts.isEmpty()) NoneRecorded()
        attempts.forEach {
            TechnicalText("${formatTime(it.startedAtMs)} ${it.endpoint} ${it.httpStatus ?: "-"} ${it.outcome} ${it.durationMs}ms ${it.detail.orEmpty()}")
        }
    }
}

@Composable
private fun EntryListCard(title: String, entries: List<DiagnosticEntry>) {
    SectionCard(title) {
        if (entries.isEmpty()) NoneRecorded()
        entries.take(MAX_VISIBLE_ENTRIES).forEach {
            TechnicalText("${formatTime(it.atMs)} ${it.level} ${it.category} ${it.message}")
        }
    }
}

@Composable
private fun NoneRecorded() {
    Text(
        stringResource(R.string.diag_none),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val MAX_VISIBLE_ENTRIES = 25
