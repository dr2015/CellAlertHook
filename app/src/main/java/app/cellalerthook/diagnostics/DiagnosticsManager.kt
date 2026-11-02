package app.cellalerthook.diagnostics

import android.util.Log
import app.cellalerthook.data.db.DiagnosticDao
import app.cellalerthook.data.db.DiagnosticEntry
import app.cellalerthook.data.db.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Structured log. Entries carry identifiers and outcomes, never alert or screen text. */
fun interface DiagnosticsLog {
    fun log(level: LogLevel, category: String, message: String)
}

class DiagnosticsManager(
    private val dao: DiagnosticDao,
    private val scope: CoroutineScope,
    private val minimumLevel: () -> LogLevel,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiagnosticsLog {
    override fun log(level: LogLevel, category: String, message: String) {
        if (level < minimumLevel()) return
        Log.println(priorityOf(level), TAG, "[$category] $message")
        val entry = DiagnosticEntry(atMs = clock(), level = level, category = category, message = message)
        scope.launch { dao.insert(entry) }
    }

    fun observe(limit: Int = 200): Flow<List<DiagnosticEntry>> = dao.observe(limit)

    fun observeCategory(category: String, limit: Int = 40): Flow<List<DiagnosticEntry>> =
        dao.observeCategory(category, limit)

    suspend fun recent(limit: Int): List<DiagnosticEntry> = dao.recent(limit)

    suspend fun prune() = dao.prune(KEEP_ENTRIES)

    private fun priorityOf(level: LogLevel) = when (level) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    private companion object {
        const val TAG = "CellAlertHookReceiver"
        const val KEEP_ENTRIES = 2000
    }
}
