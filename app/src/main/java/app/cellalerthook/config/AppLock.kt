package app.cellalerthook.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted and slow, so a copy of the stored hash does not give up a short PIN quickly. */
object PinHash {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private val allowedLength = 6..12

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)

    fun of(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(normalize(pin).toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    fun isAllowed(pin: String) = pin.length in allowedLength && pin.all(Char::isDigit)

    /** An Arabic keyboard can type ١٢٣ one day and 123 the next, and both are the same PIN. */
    private fun normalize(pin: String) = pin.map { Character.digit(it, 10) }.joinToString("")
}

object PinThrottle {
    private const val FREE_TRIES = 5
    private const val FIRST_WAIT_MS = 30_000L
    private const val LONGEST_WAIT_MS = 3_600_000L

    fun waitMs(failures: Int): Long {
        if (failures < FREE_TRIES) return 0
        val doublings = (failures - FREE_TRIES).coerceAtMost(8)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(LONGEST_WAIT_MS)
    }
}

/**
 * Guards the screens only. It sits beside the receiver, not in front of it, so a locked app still
 * catches alerts and fires. The count of wrong tries is stored, so closing the app does not reset it.
 */
class AppLock(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.getSharedPreferences(DeviceStorage.LOCK_PREFERENCES, Context.MODE_PRIVATE)
    private val unlockedState = MutableStateFlow(false)

    val unlocked: StateFlow<Boolean> = unlockedState.asStateFlow()

    fun isPinSet(): Boolean = preferences.contains(KEY_HASH)

    /** Slow on purpose, so call it off the main thread. */
    @Synchronized
    fun setPin(pin: String) {
        storePin(pin)
        unlockedState.value = true
    }

    private fun storePin(pin: String) {
        val salt = PinHash.newSalt()
        preferences.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(PinHash.of(pin, salt)))
            .remove(KEY_FAILURES)
            .remove(KEY_BLOCKED_UNTIL)
            .apply()
    }

    /** Slow on purpose, so call it off the main thread. */
    @Synchronized
    fun tryUnlock(pin: String): Boolean = attempt {
        val salt = preferences.getString(KEY_SALT, null) ?: return@attempt false
        val hash = preferences.getString(KEY_HASH, null) ?: return@attempt false
        MessageDigest.isEqual(PinHash.of(pin, decode(salt)), decode(hash))
    }

    /**
     * First open of a preset build, where opening the sealed links proves the PIN. The PIN is
     * stored before unlocking, because storing it is slow and leaving the app meanwhile must lock it.
     */
    @Synchronized
    fun tryUnseal(pin: String, unseal: (String) -> Boolean): Boolean = attempt {
        unseal(pin).also { opened -> if (opened) storePin(pin) }
    }

    private fun attempt(matches: () -> Boolean): Boolean {
        if (waitMs() > 0) return false
        val passed = matches()
        val failures = if (passed) 0 else preferences.getInt(KEY_FAILURES, 0) + 1
        preferences.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_BLOCKED_UNTIL, clock() + PinThrottle.waitMs(failures))
            .apply()
        unlockedState.value = passed
        return passed
    }

    fun waitMs(): Long = (preferences.getLong(KEY_BLOCKED_UNTIL, 0) - clock()).coerceAtLeast(0)

    fun lock() {
        unlockedState.value = false
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_BLOCKED_UNTIL = "pin_blocked_until"
    }
}
