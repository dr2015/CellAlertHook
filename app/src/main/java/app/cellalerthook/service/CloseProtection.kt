package app.cellalerthook.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.cellalerthook.CellAlertHookApplication
import app.cellalerthook.R
import app.cellalerthook.data.db.LogLevel

/**
 * Android greys out Force stop, Clear storage and Uninstall for an active device admin app, which
 * keeps the receiver running on a phone other people can pick up. It asks for no policies, so it
 * cannot lock or wipe the phone. Someone past the phone's own screen lock can still remove it in
 * Settings. Only a dedicated phone set up as device owner closes that gap. See docs/SETUP.md.
 */
class CloseProtectionReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.close_protection_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        val container = (context.applicationContext as CellAlertHookApplication).container
        container.diagnostics.log(LogLevel.WARN, "service", "close_protection_removed")
    }

    companion object {
        private fun component(context: Context) = ComponentName(context, CloseProtectionReceiver::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))

        /** Android shows its own consent screen, so only a person at the phone can turn this on. */
        fun enableIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.close_protection_explanation))
    }
}
