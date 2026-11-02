package app.cellalerthook.service

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake long enough to store and send an alert. Radio traffic wakes the
 * device for a moment, but that lock belongs to the telephony stack and ends with it.
 */
class WakeGuard(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cellalerthook:alert-processing")
        .apply { setReferenceCounted(false) }

    /** Timed, so a stuck request can never hold the device awake indefinitely. */
    fun hold() {
        lock.acquire(HOLD_MS)
    }

    private companion object {
        const val HOLD_MS = 60_000L
    }
}
