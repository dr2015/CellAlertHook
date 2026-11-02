package app.cellalerthook.config

import android.content.Context
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.detection.KnownAlertPackages
import app.cellalerthook.domain.AlertFingerprint
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.ProcessorSettings
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.TriggerMethod
import app.cellalerthook.domain.TriggerSchedule
import app.cellalerthook.domain.TriggerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.DayOfWeek

@Serializable
data class ReceiverConfig(
    val serverHost: String = "",
    val serverPort: Int = 8443,
    val useTls: Boolean = true,
    val alertsPath: String = "/api/emergency/alerts",
    /** Simulations go here, so a test can never reach the production route. */
    val testAlertsPath: String = "/api/emergency/test-alerts",
    val heartbeatPath: String = "/api/emergency/heartbeat",
    val healthPath: String = "/api/emergency/health",
    val deviceId: String = "cellalerthook-receiver-01",
    val enabledMethods: Set<CaptureMethod> = setOf(
        CaptureMethod.CELL_BROADCAST_PROVIDER,
        CaptureMethod.ACCESSIBILITY,
        CaptureMethod.NOTIFICATION,
    ),
    val packageAllowlist: Set<String> = KnownAlertPackages.DEFAULT_ALLOWLIST,
    val retryBaseSeconds: Int = 5,
    val retryMaxSeconds: Int = 300,
    /** 0 keeps retrying: a real alert is never abandoned by default. */
    val retryMaxAttempts: Int = 0,
    val requestTimeoutSeconds: Int = 10,
    val duplicateWindowMinutes: Int = 30,
    val cellBroadcastDuplicateWindowHours: Int = 24,
    val heartbeatSeconds: Int = 300,
    val logLevel: LogLevel = LogLevel.INFO,
    /** PEM of the certificate or private CA to trust. Blank means the system trust store. */
    val pinnedCaPem: String = "",
    /** Off until someone deliberately arms it, so a new install can never call a URL. */
    val triggerArmed: Boolean = false,
    val triggerMethod: TriggerMethod = TriggerMethod.GET,
    val activatePhrases: List<String> = DEFAULT_ACTIVATE_PHRASES,
    val clearPhrases: List<String> = DEFAULT_CLEAR_PHRASES,
    val triggerMaxAgeMinutes: Int = 30,
    /** Off by default, so an alert is never held back by hours nobody has set. */
    val scheduleEnabled: Boolean = false,
    /** ISO day numbers, Monday 1 to Sunday 7. The default is Monday to Friday. */
    val scheduleDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val scheduleStartMinute: Int = 8 * 60,
    val scheduleEndMinute: Int = 17 * 60,
    /** Only debug provisioning sets this. No screen can, and a release build has no way to. */
    val allowLabTriggers: Boolean = false,
) {
    val isServerConfigured: Boolean get() = serverHost.isNotBlank()

    fun triggerSettings() = TriggerSettings(
        armed = triggerArmed,
        activatePhrases = activatePhrases,
        clearPhrases = clearPhrases,
        maxAgeMs = triggerMaxAgeMinutes * 60_000L,
        allowLabAlerts = allowLabTriggers,
        schedule = TriggerSchedule(
            enabled = scheduleEnabled,
            days = scheduleDays.filter { it in 1..7 }.map(DayOfWeek::of).toSet(),
            startMinute = scheduleStartMinute,
            endMinute = scheduleEndMinute,
        ),
    )

    fun url(path: String): String {
        val scheme = if (useTls) "https" else "http"
        val host = if (serverHost.contains(':') && !serverHost.startsWith('[')) "[$serverHost]" else serverHost
        return "$scheme://$host:$serverPort$path"
    }

    fun processorSettings() = ProcessorSettings(
        deviceId = deviceId,
        duplicateWindowMs = duplicateWindowMinutes * 60_000L,
        cellBroadcastDuplicateWindowMs = cellBroadcastDuplicateWindowHours * 3_600_000L,
    )

    companion object {
        /** Taken from real alerts in one country. Set your own in Settings. See docs/URL_TRIGGER.md. */
        val DEFAULT_ACTIVATE_PHRASES = listOf("لتهديد جوي معادي", "hostile aerial threat")
        val DEFAULT_CLEAR_PHRASES = listOf("زال الخطر", "threat has passed")
    }
}

enum class ConfigProblem {
    SERVER_TLS_REQUIRED,
    TRIGGER_URL_INVALID,
    TRIGGER_URL_MISSING,
    TRIGGER_PHRASE_INVALID,
    TRIGGER_AGE_INVALID,
    SCHEDULE_INVALID,
    HOST_INVALID,
    PORT_INVALID,
    PATH_INVALID,
    TEST_PATH_EQUALS_PRODUCTION,
    DEVICE_ID_INVALID,
    RETRY_INVALID,
    TIMEOUT_INVALID,
    WINDOW_INVALID,
}

object ConfigValidator {
    private const val MIN_PHRASE_LENGTH = 6
    private val deviceId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val path = Regex("^/[A-Za-z0-9._~/%-]*$")

    fun validate(config: ReceiverConfig): Set<ConfigProblem> = buildSet {
        if (config.serverHost.isNotBlank() && !isValidHost(config.serverHost)) add(ConfigProblem.HOST_INVALID)
        if (config.serverPort !in 1..65535) add(ConfigProblem.PORT_INVALID)
        val paths = listOf(config.alertsPath, config.testAlertsPath, config.heartbeatPath, config.healthPath)
        if (paths.any { !path.matches(it) }) add(ConfigProblem.PATH_INVALID)
        if (config.testAlertsPath.trimEnd('/') == config.alertsPath.trimEnd('/')) {
            add(ConfigProblem.TEST_PATH_EQUALS_PRODUCTION)
        }
        if (!deviceId.matches(config.deviceId)) add(ConfigProblem.DEVICE_ID_INVALID)
        if (config.retryBaseSeconds < 1 || config.retryMaxSeconds < config.retryBaseSeconds || config.retryMaxAttempts < 0) {
            add(ConfigProblem.RETRY_INVALID)
        }
        if (config.requestTimeoutSeconds !in 1..120) add(ConfigProblem.TIMEOUT_INVALID)
        if (config.duplicateWindowMinutes !in 1..1440) add(ConfigProblem.WINDOW_INVALID)
        if (config.isServerConfigured && !config.useTls && !isPrivateHost(config.serverHost)) {
            add(ConfigProblem.SERVER_TLS_REQUIRED)
        }
        if ((config.activatePhrases + config.clearPhrases).any { !isUsablePhrase(it) }) {
            add(ConfigProblem.TRIGGER_PHRASE_INVALID)
        }
        if (config.triggerArmed && (config.activatePhrases.isEmpty() || config.clearPhrases.isEmpty())) {
            add(ConfigProblem.TRIGGER_PHRASE_INVALID)
        }
        if (config.triggerMaxAgeMinutes !in 1..1440) add(ConfigProblem.TRIGGER_AGE_INVALID)
        val hoursValid = config.scheduleStartMinute in 0..1439 && config.scheduleEndMinute in 1..1439 &&
            config.scheduleStartMinute < config.scheduleEndMinute
        // With no day selected the on URL could never be called, which should be a choice to disarm.
        if (!hoursValid || (config.scheduleEnabled && config.scheduleDays.isEmpty())) add(ConfigProblem.SCHEDULE_INVALID)
    }

    fun validateTriggerUrls(armed: Boolean, activateUrl: String, clearUrl: String): Set<ConfigProblem> = buildSet {
        val urls = listOf(activateUrl.trim(), clearUrl.trim())
        if (armed && urls.any { it.isEmpty() }) add(ConfigProblem.TRIGGER_URL_MISSING)
        if (urls.any { it.isNotEmpty() && !isValidTriggerUrl(it) }) add(ConfigProblem.TRIGGER_URL_INVALID)
    }

    /** A very short phrase would match nearly every alert and fire for all of them. */
    private fun isUsablePhrase(phrase: String) = AlertFingerprint.normalize(phrase).length >= MIN_PHRASE_LENGTH

    private fun isValidTriggerUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> uri.userInfo == null
            // Credentials in the URL would not be sent, so the target would just refuse the call.
            "http" -> uri.userInfo == null && isPrivateHost(host)
            else -> false
        }
    }

    /** Devices on a private network rarely have TLS. Anywhere else, cleartext is refused. */
    fun isPrivateHost(host: String): Boolean {
        val name = host.lowercase().removeSurrounding("[", "]")
        if (name == "localhost" || name == "::1" || name.endsWith(".local")) return true
        if (name.startsWith("fe80:") || name.startsWith("fc") || name.startsWith("fd")) return name.contains(':')
        val octets = name.split('.').map { it.toIntOrNull() }
        if (octets.size == 1) return !name.contains(':')
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val (first, second) = octets.map { it!! }
        return first == 10 || first == 127 || (first == 192 && second == 168) ||
            (first == 169 && second == 254) || (first == 172 && second in 16..31)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.contains("://") || host.contains('/') || host.any { it.isWhitespace() }) return false
        return runCatching {
            val bracketed = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            URI("http://$bracketed:1/").host != null
        }.getOrDefault(false)
    }
}

class DeviceConfiguration(
    context: Context,
    private val credentials: SecureCredentialStore,
) {
    private val preferences = context.getSharedPreferences(DeviceStorage.CONFIG_PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val state = MutableStateFlow(load())

    val config: StateFlow<ReceiverConfig> = state.asStateFlow()

    fun current(): ReceiverConfig = state.value

    @Synchronized
    fun update(transform: (ReceiverConfig) -> ReceiverConfig): Set<ConfigProblem> {
        val updated = transform(state.value)
        val problems = ConfigValidator.validate(updated)
        if (problems.isEmpty()) {
            preferences.edit().putString(KEY_CONFIG, json.encodeToString(updated)).apply()
            state.value = updated
        }
        return problems
    }

    fun authToken(): String? = credentials.read(KEY_TOKEN)

    fun hasAuthToken(): Boolean = credentials.contains(KEY_TOKEN)

    fun setAuthToken(token: String?) {
        if (token.isNullOrBlank()) credentials.remove(KEY_TOKEN) else credentials.write(KEY_TOKEN, token.trim())
    }

    /** Kept encrypted, because a controller's URL often contains its key. */
    fun triggerUrl(action: TriggerAction): String? = credentials.read(urlKey(action))

    fun hasTriggerUrl(action: TriggerAction): Boolean = credentials.contains(urlKey(action))

    fun setTriggerUrl(action: TriggerAction, url: String?) {
        if (url.isNullOrBlank()) credentials.remove(urlKey(action)) else credentials.write(urlKey(action), url.trim())
    }

    private fun urlKey(action: TriggerAction) = if (action == TriggerAction.ACTIVATE) KEY_ACTIVATE_URL else KEY_CLEAR_URL

    private fun load(): ReceiverConfig {
        val stored = preferences.getString(KEY_CONFIG, null) ?: return ReceiverConfig()
        return runCatching { json.decodeFromString<ReceiverConfig>(stored) }.getOrDefault(ReceiverConfig())
    }

    private companion object {
        const val KEY_CONFIG = "config_json"
        const val KEY_TOKEN = "device_auth_token"
        const val KEY_ACTIVATE_URL = "activate_url"
        const val KEY_CLEAR_URL = "clear_url"
    }
}
