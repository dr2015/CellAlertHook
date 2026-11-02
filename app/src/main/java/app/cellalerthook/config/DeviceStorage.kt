package app.cellalerthook.config

import android.content.Context
import android.os.UserManager
import app.cellalerthook.data.db.AppDatabase

/**
 * After a reboot with a screen lock, Android keeps normal app storage encrypted until the first
 * unlock, so an app using it cannot run. A phone restarted by a power cut could then miss an
 * alert. Android's own Cell Broadcast stack avoids this by using device-protected storage, which
 * is available from boot, so this app keeps everything it needs to fire there too.
 */
object DeviceStorage {
    const val CONFIG_PREFERENCES = "receiver_config"
    const val CREDENTIAL_PREFERENCES = "receiver_credentials"
    const val LOCK_PREFERENCES = "app_lock"

    fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked

    /** Also brings over data written by a version that used normal storage. */
    fun open(context: Context): Context {
        val storage = context.createDeviceProtectedStorageContext()
        if (isUserUnlocked(context)) {
            if (context.getDatabasePath(AppDatabase.NAME).exists()) storage.moveDatabaseFrom(context, AppDatabase.NAME)
            storage.moveSharedPreferencesFrom(context, CONFIG_PREFERENCES)
            storage.moveSharedPreferencesFrom(context, CREDENTIAL_PREFERENCES)
        }
        return storage
    }
}
