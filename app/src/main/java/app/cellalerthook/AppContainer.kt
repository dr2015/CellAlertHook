package app.cellalerthook

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import app.cellalerthook.config.AppLock
import app.cellalerthook.config.DeviceConfiguration
import app.cellalerthook.config.DeviceStorage
import app.cellalerthook.config.PresetSeal
import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.config.SecureCredentialStore
import app.cellalerthook.data.AlertRepository
import app.cellalerthook.data.db.AppDatabase
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.delivery.AlertDeliveryService
import app.cellalerthook.delivery.DeliveryScheduler
import app.cellalerthook.delivery.OkHttpAlertTransport
import app.cellalerthook.delivery.UrlTriggerService
import app.cellalerthook.delivery.WorkScheduler
import app.cellalerthook.detection.AccessibilityAlertDetector
import app.cellalerthook.detection.AlertPackageResolver
import app.cellalerthook.detection.CellBroadcastCapabilityChecker
import app.cellalerthook.detection.CellBroadcastProviderDetector
import app.cellalerthook.detection.DetectorRegistry
import app.cellalerthook.detection.DetectorState
import app.cellalerthook.detection.HiddenNotificationText
import app.cellalerthook.detection.NotificationAlertDetector
import app.cellalerthook.detection.ProtectedBroadcastProbe
import app.cellalerthook.diagnostics.ConnectionTester
import app.cellalerthook.diagnostics.DiagnosticsManager
import app.cellalerthook.domain.AlertPipeline
import app.cellalerthook.domain.AlertProcessor
import app.cellalerthook.domain.BuildProvenance
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.service.HealthMonitor
import app.cellalerthook.service.NetworkMonitor
import app.cellalerthook.service.ReceiverForegroundService
import app.cellalerthook.service.WakeGuard
import app.cellalerthook.simulation.SimulationManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-written dependency graph. There are few enough objects that a framework adds nothing. */
class AppContainer(private val application: Application) {
    private val context: Context get() = application

    private val failures = CoroutineExceptionHandler { _, error ->
        Log.e("CellAlertHookReceiver", "background_failure ${error.javaClass.simpleName}", error)
        diagnostics.log(LogLevel.ERROR, "app", "background_failure ${error.javaClass.simpleName}")
    }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)

    val provenance = BuildProvenance(Build.TYPE, Build.TAGS)
    private val storage: Context = DeviceStorage.open(context)
    val database = AppDatabase.create(storage)
    val repository = AlertRepository(database.alerts())
    val configuration = DeviceConfiguration(storage, SecureCredentialStore(storage))
    val appLock = AppLock(storage)

    /** Null except in a preset build. */
    private val sealedLinks: String? = runCatching {
        context.assets.open(PresetSeal.ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()

    val hasSealedLinks: Boolean get() = sealedLinks != null

    fun unsealLinks(pin: String): Boolean {
        val links = sealedLinks?.let { PresetSeal.open(it, pin) } ?: return false
        configuration.setTriggerUrl(TriggerAction.ACTIVATE, links.activateUrl)
        configuration.setTriggerUrl(TriggerAction.CLEAR, links.clearUrl)
        if (links.certificate.isNotBlank()) configuration.update { it.copy(pinnedCaPem = links.certificate) }
        return true
    }

    val diagnostics: DiagnosticsManager = DiagnosticsManager(
        dao = database.diagnostics(),
        scope = appScope,
        minimumLevel = { configuration.current().logLevel },
    )

    val packages = AlertPackageResolver(context)
    private val wakeGuard = WakeGuard(context)

    @Volatile
    private var effectiveAllowlist: Set<String> = packages.effectiveAllowlist(configuration.current().packageAllowlist)

    val network: NetworkMonitor = NetworkMonitor(context) {
        trigger.kick()
        delivery.kick()
    }
    val transport: OkHttpAlertTransport = OkHttpAlertTransport({ configuration.current() }, network)

    val trigger: UrlTriggerService = UrlTriggerService(
        store = repository,
        transport = transport,
        config = { configuration.current() },
        urlFor = configuration::triggerUrl,
        log = diagnostics,
        scheduler = DeliveryScheduler { delayMs -> WorkScheduler.scheduleTrigger(context, delayMs) },
        scope = appScope,
    )

    val delivery: AlertDeliveryService = AlertDeliveryService(
        store = repository,
        transport = transport,
        config = { configuration.current() },
        authToken = { configuration.authToken() },
        log = diagnostics,
        provenance = provenance,
        scheduler = DeliveryScheduler { delayMs -> WorkScheduler.scheduleDelivery(context, delayMs) },
        scope = appScope,
    )

    private val processor = AlertProcessor(
        store = repository,
        provenance = provenance,
        settings = { configuration.current().processorSettings() },
    )

    val pipeline = AlertPipeline(processor, repository, delivery, trigger, diagnostics, provenance, wakeGuard)

    val providerDetector = CellBroadcastProviderDetector(
        context = context,
        seenRows = repository,
        log = diagnostics,
        wakeGuard = wakeGuard,
        scope = appScope,
        isEnabled = { isEnabled(CaptureMethod.CELL_BROADCAST_PROVIDER) },
    )

    val accessibilityDetector = AccessibilityAlertDetector(
        selfPackage = context.packageName,
        packages = packages,
        allowlist = { effectiveAllowlist },
        log = diagnostics,
        scope = appScope,
        isEnabled = { isEnabled(CaptureMethod.ACCESSIBILITY) },
    )

    val notificationDetector = NotificationAlertDetector(
        selfPackage = context.packageName,
        packages = packages,
        allowlist = { effectiveAllowlist },
        log = diagnostics,
        scope = appScope,
        isEnabled = { isEnabled(CaptureMethod.NOTIFICATION) },
        hiddenTextPlaceholders = HiddenNotificationText::placeholders,
        textDetectorActive = {
            listOf(providerDetector, accessibilityDetector).any { it.status.value.state == DetectorState.ACTIVE }
        },
    )

    val registry = DetectorRegistry(listOf(providerDetector, accessibilityDetector, notificationDetector))

    private val broadcastProbe = ProtectedBroadcastProbe(context, diagnostics) {
        appScope.launch { providerDetector.refresh() }
    }

    val capabilities = CellBroadcastCapabilityChecker(context, packages, broadcastProbe)

    val health = HealthMonitor(
        context = context,
        configuration = configuration,
        registry = registry,
        repository = repository,
        network = network,
        transport = transport,
        log = diagnostics,
    )

    val connectionTester = ConnectionTester({ configuration.current() }, { configuration.authToken() }, network, transport)

    val simulation = SimulationManager(context, pipeline)

    fun refreshAllowlist() {
        effectiveAllowlist = packages.effectiveAllowlist(configuration.current().packageAllowlist)
    }

    fun start() {
        registry.startAll(pipeline)
        network.start()
        broadcastProbe.start()
        onUserUnlocked()
        ReceiverForegroundService.start(context, diagnostics)
        trigger.kick()
        delivery.kick()
    }

    /** WorkManager only works after the first unlock. Before that, the foreground service does the work. */
    fun onUserUnlocked() {
        WorkScheduler.scheduleWatchdog(context)
        WorkScheduler.scheduleProviderTrigger(context)
    }

    private fun isEnabled(method: CaptureMethod): Boolean =
        method in configuration.current().enabledMethods

    fun updateConfig(transform: (ReceiverConfig) -> ReceiverConfig) = configuration.update(transform).also {
        refreshAllowlist()
    }
}
