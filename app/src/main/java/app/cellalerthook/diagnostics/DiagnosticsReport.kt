package app.cellalerthook.diagnostics

import app.cellalerthook.BuildConfig
import app.cellalerthook.data.db.DiagnosticEntry
import app.cellalerthook.data.db.EvidenceRecord
import app.cellalerthook.detection.CapabilityReport
import app.cellalerthook.service.HealthSnapshot
import java.time.Instant

/** Plain-text report for support. It is built from identifiers and counts, never alert text. */
object DiagnosticsReport {
    fun build(
        capability: CapabilityReport,
        evidence: List<EvidenceRecord>,
        health: HealthSnapshot,
        log: List<DiagnosticEntry>,
    ): String = buildString {
        appendLine("Cell Alert Hook ${BuildConfig.VERSION_NAME} diagnostics")
        appendLine("generated ${Instant.now()}")
        appendLine()
        appendLine("[device]")
        appendLine("${capability.manufacturer} ${capability.model}")
        appendLine("android ${capability.release} api ${capability.sdkInt} patch ${capability.securityPatch}")
        appendLine("build ${capability.buildType} ${capability.buildTags}")
        appendLine("sim ${capability.simOperator} network ${capability.networkOperator}")
        appendLine()
        appendLine("[capability]")
        appendLine("verdict ${capability.verdict}")
        appendLine("${capability.readPermission.name} ${capability.readPermission.protection} granted=${capability.readPermission.granted}")
        appendLine("${capability.emergencyPermission.name} ${capability.emergencyPermission.protection} granted=${capability.emergencyPermission.granted}")
        appendLine("provider ${capability.providerPackage} read=${capability.providerReadPermission}")
        appendLine("history ${capability.historyQuery.outcome} rows=${capability.historyQuery.rows}")
        appendLine("full_table ${capability.fullTableQuery.outcome}")
        appendLine("protected_broadcasts_received ${capability.protectedBroadcastsReceived}")
        capability.alertReceivers.forEach {
            appendLine("alert_receiver ${it.packageName} system=${it.system} enabled=${it.enabled} version=${it.versionName}")
        }
        appendLine()
        appendLine("[health]")
        appendLine("service_running ${health.serviceRunning}")
        health.detectors.forEach { appendLine("detector ${it.id} ${it.state} ${it.detail}") }
        appendLine("battery_optimization_ignored ${health.batteryOptimizationIgnored}")
        appendLine("server ${health.server.state} ${health.server.detail}")
        appendLine("network ${health.network}")
        appendLine()
        appendLine("[evidence]")
        if (evidence.isEmpty()) appendLine("none")
        evidence.forEach {
            appendLine("${it.kind} ${it.provenance} count=${it.count} last=${Instant.ofEpochMilli(it.lastAtMs)}")
        }
        appendLine()
        appendLine("[log]")
        log.forEach { appendLine("${Instant.ofEpochMilli(it.atMs)} ${it.level} ${it.category} ${it.message}") }
    }
}
