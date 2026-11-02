package app.cellalerthook.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.diagnostics.DiagnosticsLog
import java.util.concurrent.atomic.AtomicInteger

data class PermissionProbe(
    val name: String,
    val definedOnDevice: Boolean,
    val protection: String,
    val granted: Boolean,
)

enum class QueryOutcome { READABLE, DENIED, FAILED }

data class QueryProbe(val outcome: QueryOutcome, val rows: Int?, val detail: String?)

enum class CapabilityVerdict {
    /** This app can read structured Cell Broadcast rows from the OS. */
    DIRECT_READ_AVAILABLE,

    /** The provider exists but the permission has not been granted yet. */
    DIRECT_READ_NEEDS_GRANT,

    /** The device has no readable provider: only UI capture can work. */
    DIRECT_READ_NOT_AVAILABLE,
}

data class CapabilityReport(
    val sdkInt: Int,
    val release: String,
    val securityPatch: String,
    val manufacturer: String,
    val model: String,
    val buildType: String,
    val buildTags: String,
    val simOperator: String,
    val networkOperator: String,
    val readPermission: PermissionProbe,
    val emergencyPermission: PermissionProbe,
    val providerPackage: String?,
    val providerReadPermission: String?,
    val historyQuery: QueryProbe,
    val fullTableQuery: QueryProbe,
    val alertReceivers: List<PackageProbe>,
    val protectedBroadcastsReceived: Int,
    val verdict: CapabilityVerdict,
)

/**
 * Probes the running device rather than assuming: permission levels, provider presence and
 * the alert packages all vary by OEM and version, and an SDK constant proves nothing.
 */
class CellBroadcastCapabilityChecker(
    private val context: Context,
    private val packages: AlertPackageResolver,
    private val broadcastProbe: ProtectedBroadcastProbe,
) {
    fun check(): CapabilityReport {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val read = permission(CellBroadcastProviderDetector.PERMISSION)
        val provider = context.packageManager.resolveContentProvider(CellBroadcastProviderDetector.AUTHORITY, 0)
        val history = query(CellBroadcastProviderDetector.HISTORY_URI)
        return CapabilityReport(
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            buildType = Build.TYPE,
            buildTags = Build.TAGS,
            simOperator = telephony?.simOperator.orEmpty(),
            networkOperator = telephony?.networkOperator.orEmpty(),
            readPermission = read,
            emergencyPermission = permission(EMERGENCY_PERMISSION),
            providerPackage = provider?.packageName,
            providerReadPermission = provider?.readPermission,
            historyQuery = history,
            fullTableQuery = query(CellBroadcastProviderDetector.ROOT_URI),
            alertReceivers = packages.discoverSystemReceivers().map(packages::probe),
            protectedBroadcastsReceived = broadcastProbe.received,
            verdict = verdict(provider != null, history),
        )
    }

    private fun verdict(providerPresent: Boolean, history: QueryProbe): CapabilityVerdict = when {
        !providerPresent -> CapabilityVerdict.DIRECT_READ_NOT_AVAILABLE
        history.outcome == QueryOutcome.READABLE -> CapabilityVerdict.DIRECT_READ_AVAILABLE
        history.outcome == QueryOutcome.DENIED -> CapabilityVerdict.DIRECT_READ_NEEDS_GRANT
        else -> CapabilityVerdict.DIRECT_READ_NOT_AVAILABLE
    }

    private fun permission(name: String): PermissionProbe {
        val granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        return try {
            val info = context.packageManager.getPermissionInfo(name, 0)
            PermissionProbe(name, definedOnDevice = true, protection = describe(info), granted = granted)
        } catch (_: PackageManager.NameNotFoundException) {
            PermissionProbe(name, definedOnDevice = false, protection = "undefined", granted = granted)
        }
    }

    private fun describe(info: PermissionInfo): String {
        val base = when (info.protection) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            PermissionInfo.PROTECTION_INTERNAL -> "internal"
            else -> "unknown"
        }
        return if (info.protectionFlags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) "$base|privileged" else base
    }

    private fun query(uri: Uri): QueryProbe = try {
        context.contentResolver.query(uri, arrayOf("_id"), null, null, null).use { cursor ->
            if (cursor == null) {
                QueryProbe(QueryOutcome.FAILED, null, "no_cursor")
            } else {
                QueryProbe(QueryOutcome.READABLE, cursor.count, null)
            }
        }
    } catch (e: SecurityException) {
        QueryProbe(QueryOutcome.DENIED, null, e.message?.take(160))
    } catch (e: IllegalArgumentException) {
        QueryProbe(QueryOutcome.FAILED, null, e.message?.take(160))
    }

    private companion object {
        const val EMERGENCY_PERMISSION = "android.permission.RECEIVE_EMERGENCY_BROADCAST"
    }
}

/**
 * Listens for the protected emergency broadcast. AOSP sends it to the alert app by explicit
 * package, so on a stock device this stays at zero. It exists so that a device behaving
 * differently is reported as a finding rather than assumed away.
 */
class ProtectedBroadcastProbe(
    private val context: Context,
    private val log: DiagnosticsLog,
    private val onReceived: () -> Unit,
) {
    private val count = AtomicInteger()
    private var registered = false

    val received: Int get() = count.get()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            count.incrementAndGet()
            log.log(LogLevel.WARN, "cell_broadcast", "protected_broadcast_reached_app action=${intent.action}")
            onReceived()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(KnownAlertPackages.ACTION_EMERGENCY_CB)
            addAction(ACTION_CB)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private companion object {
        const val ACTION_CB = "android.provider.Telephony.SMS_CB_RECEIVED"
    }
}
