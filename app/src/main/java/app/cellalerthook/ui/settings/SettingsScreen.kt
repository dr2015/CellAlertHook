package app.cellalerthook.ui.settings

import android.app.LocaleManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.cellalerthook.AppContainer
import app.cellalerthook.R
import app.cellalerthook.config.ConfigProblem
import app.cellalerthook.config.ConfigValidator
import app.cellalerthook.config.ReceiverConfig
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.domain.TriggerMethod
import app.cellalerthook.domain.TriggerSchedule
import app.cellalerthook.ui.SectionCard
import app.cellalerthook.ui.StatusLevel
import app.cellalerthook.ui.lock.NewPinForm
import app.cellalerthook.ui.statusColor
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Sunday first, because the days can start the week either way. */
private val WEEK = listOf(
    DayOfWeek.SUNDAY,
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
)

/** Text fields hold raw input; nothing reaches the live config until it validates. */
private data class SettingsForm(
    val host: String,
    val port: String,
    val useTls: Boolean,
    val alertsPath: String,
    val testPath: String,
    val token: String,
    val caPem: String,
    val deviceId: String,
    val methods: Set<CaptureMethod>,
    val allowlist: String,
    val retryBase: String,
    val retryMax: String,
    val retryAttempts: String,
    val timeout: String,
    val logLevel: LogLevel,
    val armed: Boolean,
    val activateUrl: String,
    val clearUrl: String,
    val triggerMethod: TriggerMethod,
    val activatePhrases: String,
    val clearPhrases: String,
    val triggerAge: String,
    val scheduleEnabled: Boolean,
    val scheduleDays: Set<Int>,
    val scheduleStart: String,
    val scheduleEnd: String,
) {
    fun applyTo(config: ReceiverConfig) = config.copy(
        triggerArmed = armed,
        scheduleEnabled = scheduleEnabled,
        scheduleDays = scheduleDays,
        scheduleStartMinute = TriggerSchedule.parseMinutes(scheduleStart) ?: -1,
        scheduleEndMinute = TriggerSchedule.parseMinutes(scheduleEnd) ?: -1,
        triggerMethod = triggerMethod,
        activatePhrases = phraseLines(activatePhrases),
        clearPhrases = phraseLines(clearPhrases),
        triggerMaxAgeMinutes = triggerAge.trim().toIntOrNull() ?: -1,
        serverHost = host.trim(),
        serverPort = port.trim().toIntOrNull() ?: -1,
        useTls = useTls,
        alertsPath = alertsPath.trim(),
        testAlertsPath = testPath.trim(),
        pinnedCaPem = caPem.trim(),
        deviceId = deviceId.trim(),
        enabledMethods = methods,
        packageAllowlist = allowlist.lines().map(String::trim).filter(String::isNotEmpty).toSet(),
        retryBaseSeconds = retryBase.trim().toIntOrNull() ?: -1,
        retryMaxSeconds = retryMax.trim().toIntOrNull() ?: -1,
        retryMaxAttempts = retryAttempts.trim().toIntOrNull() ?: -1,
        requestTimeoutSeconds = timeout.trim().toIntOrNull() ?: -1,
        logLevel = logLevel,
    )

    private fun phraseLines(text: String) = text.lines().map(String::trim).filter(String::isNotEmpty)

    companion object {
        /** The link fields always start empty. A saved link is used by the app but never shown. */
        fun from(config: ReceiverConfig) = SettingsForm(
            armed = config.triggerArmed,
            activateUrl = "",
            clearUrl = "",
            triggerMethod = config.triggerMethod,
            activatePhrases = config.activatePhrases.joinToString("\n"),
            clearPhrases = config.clearPhrases.joinToString("\n"),
            triggerAge = config.triggerMaxAgeMinutes.toString(),
            scheduleEnabled = config.scheduleEnabled,
            scheduleDays = config.scheduleDays,
            scheduleStart = TriggerSchedule.formatMinutes(config.scheduleStartMinute),
            scheduleEnd = TriggerSchedule.formatMinutes(config.scheduleEndMinute),
            host = config.serverHost,
            port = config.serverPort.toString(),
            useTls = config.useTls,
            alertsPath = config.alertsPath,
            testPath = config.testAlertsPath,
            token = "",
            caPem = config.pinnedCaPem,
            deviceId = config.deviceId,
            methods = config.enabledMethods,
            allowlist = config.packageAllowlist.sorted().joinToString("\n"),
            retryBase = config.retryBaseSeconds.toString(),
            retryMax = config.retryMaxSeconds.toString(),
            retryAttempts = config.retryMaxAttempts.toString(),
            timeout = config.requestTimeoutSeconds.toString(),
            logLevel = config.logLevel,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = container.configuration
    var form by remember { mutableStateOf(SettingsForm.from(configuration.current())) }
    var problems by remember { mutableStateOf<Set<ConfigProblem>?>(null) }
    var tokenSaved by remember { mutableStateOf(configuration.hasAuthToken()) }
    var activateSaved by remember { mutableStateOf(configuration.hasTriggerUrl(TriggerAction.ACTIVATE)) }
    var clearSaved by remember { mutableStateOf(configuration.hasTriggerUrl(TriggerAction.CLEAR)) }
    var linkCheck by remember { mutableStateOf<LinkCheck?>(null) }

    fun change(updated: SettingsForm) {
        form = updated
        problems = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.settings_trigger)) {
            ToggleRow(R.string.settings_trigger_armed, form.armed) { change(form.copy(armed = it)) }
            SecretField(R.string.settings_activate_url, form.activateUrl, linkNote(activateSaved)) { change(form.copy(activateUrl = it)) }
            SecretField(R.string.settings_clear_url, form.clearUrl, linkNote(clearSaved)) { change(form.copy(clearUrl = it)) }
            OutlinedButton(
                onClick = {
                    linkCheck = LinkCheck.RUNNING
                    scope.launch { linkCheck = checkLinks(container) }
                },
                enabled = linkCheck != LinkCheck.RUNNING,
            ) {
                Text(stringResource(R.string.settings_check_links))
            }
            linkCheck?.takeIf { it != LinkCheck.RUNNING }?.let { result ->
                Text(
                    if (result.passed) stringResource(R.string.settings_check_ok) else stringResource(R.string.settings_check_failed, result.detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(if (result.passed) StatusLevel.OK else StatusLevel.BAD),
                )
            }
            Text(stringResource(R.string.settings_trigger_method), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TriggerMethod.entries.forEach { method ->
                    FilterChip(
                        selected = form.triggerMethod == method,
                        onClick = { change(form.copy(triggerMethod = method)) },
                        label = { Text(method.name) },
                    )
                }
            }
            Field(R.string.settings_activate_phrases, form.activatePhrases, { change(form.copy(activatePhrases = it)) }, singleLine = false, freeText = true)
            Field(R.string.settings_clear_phrases, form.clearPhrases, { change(form.copy(clearPhrases = it)) }, singleLine = false, freeText = true)
            Field(R.string.settings_trigger_age, form.triggerAge, { change(form.copy(triggerAge = it)) }, numeric = true)
        }

        AppLockCard(container)

        SectionCard(stringResource(R.string.settings_schedule)) {
            ToggleRow(R.string.settings_schedule_enabled, form.scheduleEnabled) { change(form.copy(scheduleEnabled = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WEEK.forEach { day ->
                    FilterChip(
                        selected = day.value in form.scheduleDays,
                        onClick = {
                            val days = if (day.value in form.scheduleDays) form.scheduleDays - day.value else form.scheduleDays + day.value
                            change(form.copy(scheduleDays = days))
                        },
                        label = { Text(day.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())) },
                    )
                }
            }
            Field(R.string.settings_schedule_start, form.scheduleStart, { change(form.copy(scheduleStart = it)) }, technical = true)
            Field(R.string.settings_schedule_end, form.scheduleEnd, { change(form.copy(scheduleEnd = it)) }, technical = true)
            Text(
                stringResource(R.string.settings_schedule_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.settings_server)) {
            Field(R.string.settings_host, form.host, { change(form.copy(host = it)) }, technical = true)
            Field(R.string.settings_port, form.port, { change(form.copy(port = it)) }, technical = true, numeric = true)
            ToggleRow(R.string.settings_use_tls, form.useTls) { change(form.copy(useTls = it)) }
            Field(R.string.settings_alerts_path, form.alertsPath, { change(form.copy(alertsPath = it)) }, technical = true)
            Field(R.string.settings_test_path, form.testPath, { change(form.copy(testPath = it)) }, technical = true)
            Field(R.string.settings_device_id, form.deviceId, { change(form.copy(deviceId = it)) }, technical = true)
            SecretField(
                R.string.settings_token,
                form.token,
                if (tokenSaved) R.string.settings_token_saved else R.string.settings_token_empty,
            ) { change(form.copy(token = it)) }
            Field(R.string.settings_ca, form.caPem, { change(form.copy(caPem = it)) }, technical = true, singleLine = false)
        }

        SectionCard(stringResource(R.string.settings_detection)) {
            MethodToggle(R.string.label_cell_broadcast, CaptureMethod.CELL_BROADCAST_PROVIDER, form) { change(it) }
            MethodToggle(R.string.label_accessibility, CaptureMethod.ACCESSIBILITY, form) { change(it) }
            MethodToggle(R.string.label_notification, CaptureMethod.NOTIFICATION, form) { change(it) }
            Field(R.string.settings_allowlist, form.allowlist, { change(form.copy(allowlist = it)) }, technical = true, singleLine = false)
        }

        SectionCard(stringResource(R.string.settings_retry)) {
            Field(R.string.settings_retry_base, form.retryBase, { change(form.copy(retryBase = it)) }, numeric = true)
            Field(R.string.settings_retry_max, form.retryMax, { change(form.copy(retryMax = it)) }, numeric = true)
            Field(R.string.settings_retry_attempts, form.retryAttempts, { change(form.copy(retryAttempts = it)) }, numeric = true)
            Field(R.string.settings_timeout, form.timeout, { change(form.copy(timeout = it)) }, numeric = true)
        }

        SectionCard(stringResource(R.string.settings_logging)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = form.logLevel == level,
                        onClick = { change(form.copy(logLevel = level)) },
                        label = { Text(level.name) },
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LanguageCard()
        }

        problems?.let { current ->
            if (current.isEmpty()) {
                Text(stringResource(R.string.settings_saved), color = statusColor(StatusLevel.OK))
            } else {
                val names = current.map { stringResource(problemLabel(it)) }.joinToString(", ")
                Text("${stringResource(R.string.settings_invalid)} $names", color = statusColor(StatusLevel.BAD))
            }
        }

        Button(
            onClick = {
                val urlProblems = ConfigValidator.validateTriggerUrls(
                    form.armed,
                    form.activateUrl.ifBlank { configuration.triggerUrl(TriggerAction.ACTIVATE).orEmpty() },
                    form.clearUrl.ifBlank { configuration.triggerUrl(TriggerAction.CLEAR).orEmpty() },
                )
                val result = urlProblems.ifEmpty { container.updateConfig(form::applyTo) }
                if (result.isEmpty() && (form.activateUrl.isNotBlank() || form.clearUrl.isNotBlank())) {
                    if (form.activateUrl.isNotBlank()) configuration.setTriggerUrl(TriggerAction.ACTIVATE, form.activateUrl)
                    if (form.clearUrl.isNotBlank()) configuration.setTriggerUrl(TriggerAction.CLEAR, form.clearUrl)
                    activateSaved = configuration.hasTriggerUrl(TriggerAction.ACTIVATE)
                    clearSaved = configuration.hasTriggerUrl(TriggerAction.CLEAR)
                    form = form.copy(activateUrl = "", clearUrl = "")
                    // A pasted link would otherwise stay readable on the clipboard.
                    context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                }
                if (result.isEmpty() && form.token.isNotBlank()) {
                    configuration.setAuthToken(form.token)
                    tokenSaved = true
                    form = form.copy(token = "")
                }
                problems = result
                if (result.isEmpty()) {
                    container.trigger.kick()
                    container.delivery.kick()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

private fun linkNote(saved: Boolean) = if (saved) R.string.settings_link_saved else R.string.settings_link_empty

private data class LinkCheck(val passed: Boolean, val detail: String = "") {
    companion object {
        val RUNNING = LinkCheck(false, "running")
    }
}

/** Connects and checks the certificate, then hangs up, so it cannot fire anything. */
private suspend fun checkLinks(container: AppContainer): LinkCheck {
    TriggerAction.entries.forEach { action ->
        val url = container.configuration.triggerUrl(action) ?: return LinkCheck(false, "no_link")
        runCatching { container.transport.checkConnection(url) }
            .onFailure { return LinkCheck(false, it.javaClass.simpleName) }
    }
    return LinkCheck(true)
}

@Composable
private fun AppLockCard(container: AppContainer) {
    var changing by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.settings_lock)) {
        if (changing) {
            NewPinForm(container.appLock) { changing = false }
        } else {
            OutlinedButton(onClick = { changing = true }) { Text(stringResource(R.string.lock_change_pin)) }
        }
    }
}

private fun problemLabel(problem: ConfigProblem): Int = when (problem) {
    ConfigProblem.HOST_INVALID -> R.string.problem_host
    ConfigProblem.PORT_INVALID -> R.string.problem_port
    ConfigProblem.PATH_INVALID -> R.string.problem_path
    ConfigProblem.TEST_PATH_EQUALS_PRODUCTION -> R.string.problem_test_path
    ConfigProblem.DEVICE_ID_INVALID -> R.string.problem_device_id
    ConfigProblem.RETRY_INVALID -> R.string.problem_retry
    ConfigProblem.TIMEOUT_INVALID -> R.string.problem_timeout
    ConfigProblem.WINDOW_INVALID -> R.string.problem_window
    ConfigProblem.SERVER_TLS_REQUIRED -> R.string.problem_server_tls
    ConfigProblem.TRIGGER_URL_INVALID -> R.string.problem_trigger_url
    ConfigProblem.TRIGGER_URL_MISSING -> R.string.problem_trigger_url_missing
    ConfigProblem.TRIGGER_PHRASE_INVALID -> R.string.problem_trigger_phrase
    ConfigProblem.TRIGGER_AGE_INVALID -> R.string.problem_trigger_age
    ConfigProblem.SCHEDULE_INVALID -> R.string.problem_schedule
}

@Composable
private fun Field(
    label: Int,
    value: String,
    onChange: (String) -> Unit,
    technical: Boolean = false,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    freeText: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                numeric -> KeyboardType.Number
                freeText -> KeyboardType.Text
                else -> KeyboardType.Ascii
            },
            autoCorrectEnabled = false,
        ),
        textStyle = if (technical) {
            MaterialTheme.typography.bodyMedium.merge(TextStyle(textDirection = TextDirection.Ltr))
        } else {
            MaterialTheme.typography.bodyMedium
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Dots while typing, and copying out is off, so a saved or half-typed value cannot be read back. */
@Composable
private fun SecretField(label: Int, value: String, note: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(note)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MethodToggle(label: Int, method: CaptureMethod, form: SettingsForm, onChange: (SettingsForm) -> Unit) {
    ToggleRow(label, method in form.methods) { enabled ->
        onChange(form.copy(methods = if (enabled) form.methods + method else form.methods - method))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    SectionCard(stringResource(R.string.settings_language)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip(context, stringResource(R.string.settings_language_system), null)
            LanguageChip(context, "English", "en")
            LanguageChip(context, "العربية", "ar")
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageChip(context: Context, label: String, tag: String?) {
    val manager = context.getSystemService(LocaleManager::class.java)
    val current = manager.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
    FilterChip(
        selected = current == tag,
        onClick = {
            manager.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        },
        label = { Text(label) },
    )
}
