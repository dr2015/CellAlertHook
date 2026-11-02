package app.cellalerthook.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.cellalerthook.R
import app.cellalerthook.config.AppLock
import app.cellalerthook.config.PinHash
import app.cellalerthook.ui.StatusLevel
import app.cellalerthook.ui.statusColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A preset build brings its own PIN, so [unsealLinks] replaces creating one on the first open. */
@Composable
fun LockScreen(lock: AppLock, unsealLinks: ((String) -> Boolean)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        when {
            lock.isPinSet() -> EnterPin(lock, lock::tryUnlock)
            unsealLinks != null -> {
                Text(stringResource(R.string.lock_preset_note), style = MaterialTheme.typography.bodyMedium)
                EnterPin(lock) { pin -> lock.tryUnseal(pin, unsealLinks) }
            }
            else -> {
                Text(stringResource(R.string.lock_create_note), style = MaterialTheme.typography.bodyMedium)
                NewPinForm(lock)
            }
        }
    }
}

@Composable
private fun EnterPin(lock: AppLock, unlock: (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(lock.waitMs()) }

    LaunchedEffect(waitMs > 0) {
        while (waitMs > 0) {
            delay(1_000)
            waitMs = lock.waitMs()
        }
    }

    PinField(R.string.lock_enter_pin, pin) {
        pin = it
        wrong = false
    }
    if (waitMs > 0) {
        Text(stringResource(R.string.lock_wait, (waitMs + 999) / 1_000), color = statusColor(StatusLevel.BAD))
    } else if (wrong) {
        Text(stringResource(R.string.lock_wrong), color = statusColor(StatusLevel.BAD))
    }
    Button(
        onClick = {
            val attempt = pin
            pin = ""
            scope.launch {
                wrong = !withContext(Dispatchers.Default) { unlock(attempt) }
                waitMs = lock.waitMs()
            }
        },
        enabled = waitMs == 0L && pin.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.lock_unlock))
    }
}

/** Also changes the PIN from Settings. Nobody else can pick the PIN, so no build carries one. */
@Composable
fun NewPinForm(lock: AppLock, onSaved: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }

    PinField(R.string.lock_new_pin, pin) {
        pin = it
        problem = null
    }
    PinField(R.string.lock_repeat_pin, repeated) {
        repeated = it
        problem = null
    }
    problem?.let { Text(stringResource(it), color = statusColor(StatusLevel.BAD)) }
    Button(
        onClick = {
            problem = when {
                !PinHash.isAllowed(pin) -> R.string.lock_pin_invalid
                pin != repeated -> R.string.lock_pin_mismatch
                else -> null
            }
            if (problem == null) {
                scope.launch {
                    withContext(Dispatchers.Default) { lock.setPin(pin) }
                    onSaved()
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.lock_save_pin))
    }
}

@Composable
private fun PinField(label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
