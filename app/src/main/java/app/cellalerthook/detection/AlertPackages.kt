package app.cellalerthook.detection

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import app.cellalerthook.domain.OriginTrust

object KnownAlertPackages {
    const val GOOGLE_RECEIVER = "com.google.android.cellbroadcastreceiver"
    const val AOSP_RECEIVER = "com.android.cellbroadcastreceiver"
    const val ACTION_EMERGENCY_CB = "android.provider.action.SMS_EMERGENCY_CB_RECEIVED"

    /**
     * Only packages confirmed on real images. OEM alert apps are not guessed: the system
     * receiver of the emergency broadcast is discovered at runtime by [AlertPackageResolver].
     */
    val DEFAULT_ALLOWLIST: Set<String> = setOf(GOOGLE_RECEIVER, AOSP_RECEIVER)

    /** Use the layout and notification channels of the AOSP alert app. */
    val AOSP_DERIVED: Set<String> = setOf(GOOGLE_RECEIVER, AOSP_RECEIVER)
}

data class PackageProbe(
    val packageName: String,
    val installed: Boolean,
    val system: Boolean,
    val enabled: Boolean,
    val versionName: String?,
    val versionCode: Long?,
)

class AlertPackageResolver(private val context: Context) {
    private val packageManager = context.packageManager

    fun trustOf(packageName: String?): OriginTrust {
        if (packageName == null) return OriginTrust.UNKNOWN
        if (packageName == context.packageName) return OriginTrust.SELF
        val probe = probe(packageName)
        return when {
            !probe.installed -> OriginTrust.UNKNOWN
            probe.system -> OriginTrust.SYSTEM_IMAGE
            else -> OriginTrust.USER_INSTALLED
        }
    }

    /** System packages registered for the emergency broadcast on this device, on any OEM. */
    fun discoverSystemReceivers(): Set<String> =
        packageManager.queryBroadcastReceivers(Intent(KnownAlertPackages.ACTION_EMERGENCY_CB), 0)
            .map { it.activityInfo.packageName }
            .filter { probe(it).system }
            .toSet()

    fun effectiveAllowlist(configured: Set<String>): Set<String> = configured + discoverSystemReceivers()

    fun probe(packageName: String): PackageProbe = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val flags = info.applicationInfo?.flags ?: 0
        PackageProbe(
            packageName = packageName,
            installed = true,
            system = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            enabled = info.applicationInfo?.enabled == true,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        PackageProbe(packageName, installed = false, system = false, enabled = false, versionName = null, versionCode = null)
    }
}
