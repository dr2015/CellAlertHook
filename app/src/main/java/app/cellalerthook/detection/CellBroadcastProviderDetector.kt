package app.cellalerthook.detection

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import app.cellalerthook.data.SeenRowStore
import app.cellalerthook.data.db.LogLevel
import app.cellalerthook.diagnostics.DiagnosticsLog
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.RawAlertObservation
import app.cellalerthook.service.WakeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads `content://cellbroadcasts/history`, the system record of every Cell Broadcast the
 * device accepted. Unlike UI capture it delivers the message identifier, serial number and
 * CMAS fields, and it is unaffected by how or whether the alert was displayed.
 *
 * Rows persist across reboots, so anything received while this process was dead is found
 * by the next scan. A first-run baseline stops old history replaying as new incidents.
 */
class CellBroadcastProviderDetector(
    private val context: Context,
    private val seenRows: SeenRowStore,
    private val log: DiagnosticsLog,
    private val wakeGuard: WakeGuard,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertDetector {
    override val id = "cell_broadcast_provider"
    override val method = CaptureMethod.CELL_BROADCAST_PROVIDER

    private val statusFlow = MutableStateFlow(statusOf(DetectorState.BLOCKED, "not_started"))
    override val status: StateFlow<DetectorStatus> = statusFlow.asStateFlow()

    private val scanMutex = Mutex()
    private var sink: AlertSink? = null
    private var observerThread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private var followUpJob: Job? = null

    override fun start(sink: AlertSink) {
        this.sink = sink
        scope.launch { refresh() }
    }

    override fun stop() {
        unregisterObserver()
        sink = null
    }

    /** Also the safety net: a scan here catches anything the observer failed to report. */
    override suspend fun refresh() {
        if (!isEnabled()) {
            unregisterObserver()
            statusFlow.value = statusOf(DetectorState.DISABLED, "disabled_in_settings")
            return
        }
        val providerPackage = context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName
        if (providerPackage == null) {
            unregisterObserver()
            statusFlow.value = statusOf(DetectorState.UNAVAILABLE, "provider_not_present_on_this_device")
            return
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            unregisterObserver()
            statusFlow.value = statusOf(DetectorState.BLOCKED, "read_cell_broadcasts_not_granted")
            return
        }
        registerObserver()
        scan(providerPackage, catchUp = true)
    }

    /**
     * A row is inserted first and appears in /history only once the stack marks it broadcast,
     * and that update sends no notification (measured on Android 14). The change callback is
     * therefore only a hint: re-scan on a short schedule until the row can have appeared.
     */
    private fun onProviderChanged() {
        wakeGuard.hold()
        followUpJob?.cancel()
        followUpJob = scope.launch {
            val providerPackage = context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName
            for (wait in FOLLOW_UP_DELAYS_MS) {
                delay(wait)
                scan(providerPackage, catchUp = false)
            }
        }
    }

    private suspend fun scan(providerPackage: String?, catchUp: Boolean) = scanMutex.withLock {
        val target = sink ?: return@withLock
        val baseline = !seenRows.hasBaseline()
        try {
            val cursor = context.contentResolver.query(
                HISTORY_URI,
                null,
                null,
                null,
                CellBroadcastRowMapper.SORT_NEWEST_FIRST,
            )
            if (cursor == null) {
                statusFlow.value = statusOf(DetectorState.BLOCKED, "provider_returned_no_cursor")
                return@withLock
            }
            val processed = cursor.use { readRows(it, target, providerPackage, baseline, catchUp) }
            if (baseline) {
                seenRows.markSeen(BASELINE_MARKER, clock(), baseline = true)
                log.log(LogLevel.INFO, CATEGORY, "baseline_recorded rows=$processed")
            }
            statusFlow.value = statusFlow.value.copy(
                state = DetectorState.ACTIVE,
                detail = "observer_registered",
            )
        } catch (e: SecurityException) {
            log.log(LogLevel.WARN, CATEGORY, "provider_denied ${e.javaClass.simpleName}")
            statusFlow.value = statusOf(DetectorState.BLOCKED, "provider_denied")
        }
    }

    private suspend fun readRows(
        cursor: Cursor,
        target: AlertSink,
        providerPackage: String?,
        baseline: Boolean,
        catchUp: Boolean,
    ): Int {
        val rows = mutableListOf<Pair<String, RawAlertObservation?>>()
        val row = CursorCellBroadcastRow(cursor)
        val now = clock()
        // The baseline reads everything. Later scans stop at the newest rows, so history
        // older than the baseline can never surface as a new incident.
        var read = 0
        while (cursor.moveToNext() && (baseline || read < MAX_ROWS_PER_SCAN)) {
            read += 1
            val key = CellBroadcastRowMapper.rowKey(row) ?: continue
            if (seenRows.isSeen(key)) continue
            rows += key to CellBroadcastRowMapper.toObservation(row, providerPackage, now, catchUp)
        }
        if (rows.isEmpty()) return 0
        target.batch {
            // The cursor is newest first; submit in arrival order.
            for ((key, observation) in rows.asReversed()) {
                if (!baseline && observation != null) {
                    target.submit(observation)
                    statusFlow.value = statusFlow.value.copy(lastObservationAtMs = now)
                }
                // Only after the sink has stored it, so a crash here re-reads rather than loses it.
                seenRows.markSeen(key, now, baseline)
            }
        }
        return rows.size
    }

    private fun registerObserver() {
        if (observer != null) return
        val thread = HandlerThread("cb-provider-observer").apply { start() }
        val created = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onProviderChanged()
            }
        }
        context.contentResolver.registerContentObserver(ROOT_URI, true, created)
        observerThread = thread
        observer = created
        log.log(LogLevel.INFO, CATEGORY, "observer_registered")
    }

    private fun unregisterObserver() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        observerThread?.quitSafely()
        observerThread = null
    }

    private fun statusOf(state: DetectorState, detail: String) = DetectorStatus(id, method, state, detail)

    companion object {
        const val AUTHORITY = "cellbroadcasts"
        const val PERMISSION = "android.permission.READ_CELL_BROADCASTS"
        val ROOT_URI: Uri = Uri.parse("content://cellbroadcasts")
        val HISTORY_URI: Uri = Uri.parse("content://cellbroadcasts/history")

        private const val CATEGORY = "cell_broadcast"
        private const val MAX_ROWS_PER_SCAN = 256
        private const val BASELINE_MARKER = "baseline"
        /** Close together at first, because the URL trigger waits on this. Alerts are rare enough to afford it. */
        private val FOLLOW_UP_DELAYS_MS = longArrayOf(0) + LongArray(30) { 100 } + LongArray(16) { 250 } + LongArray(3) { 1_000 }
    }
}
