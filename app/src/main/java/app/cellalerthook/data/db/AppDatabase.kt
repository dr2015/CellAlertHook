package app.cellalerthook.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.DeliveryStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = Json.decodeFromString(value)

    @TypeConverter
    fun fromMethodSet(value: Set<CaptureMethod>): String = value.joinToString(",") { it.name }

    @TypeConverter
    fun toMethodSet(value: String): Set<CaptureMethod> =
        value.split(',').filter { it.isNotEmpty() }.map { CaptureMethod.valueOf(it) }.toSet()
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_events ORDER BY detectedAtMs DESC LIMIT :limit")
    fun observeEvents(limit: Int): Flow<List<AlertEvent>>

    @Query("SELECT * FROM alert_events WHERE eventId = :eventId")
    suspend fun find(eventId: String): AlertEvent?

    @Query("SELECT * FROM alert_events WHERE dedupKey = :key AND detectedAtMs >= :sinceMs ORDER BY detectedAtMs DESC LIMIT 1")
    suspend fun findByDedupKey(key: String, sinceMs: Long): AlertEvent?

    @Query(
        "SELECT e.* FROM alert_events e JOIN alert_observations o ON o.eventId = e.eventId " +
            "WHERE o.textSha256 = :fingerprint AND o.observedAtMs >= :sinceMs ORDER BY o.observedAtMs DESC LIMIT 1",
    )
    suspend fun findByObservedFingerprint(fingerprint: String, sinceMs: Long): AlertEvent?

    @Query("SELECT * FROM alert_events WHERE detectedAtMs >= :sinceMs AND simulated = :simulated ORDER BY detectedAtMs DESC")
    suspend fun recent(sinceMs: Long, simulated: Boolean): List<AlertEvent>

    @Query("SELECT * FROM alert_events WHERE deliveryStatus = :status AND nextAttemptAtMs <= :nowMs ORDER BY simulated ASC, detectedAtMs ASC LIMIT :limit")
    suspend fun due(status: DeliveryStatus, nowMs: Long, limit: Int): List<AlertEvent>

    @Query("SELECT MIN(nextAttemptAtMs) FROM alert_events WHERE deliveryStatus = :status")
    suspend fun nextAttemptAt(status: DeliveryStatus): Long?

    @Query("SELECT COUNT(*) FROM alert_events WHERE deliveryStatus = :status")
    fun observeCount(status: DeliveryStatus): Flow<Int>

    @Query("SELECT MAX(detectedAtMs) FROM alert_events WHERE simulated = 0")
    fun observeLastRealDetection(): Flow<Long?>

    @Query("SELECT MAX(deliveredAtMs) FROM alert_events")
    fun observeLastDelivery(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AlertEvent)

    @Update
    suspend fun update(event: AlertEvent)

    @Insert
    suspend fun insertObservation(observation: AlertObservation): Long

    @Query("SELECT * FROM alert_observations WHERE eventId = :eventId ORDER BY observedAtMs ASC")
    fun observeObservations(eventId: String): Flow<List<AlertObservation>>

    @Transaction
    suspend fun insertWithObservation(event: AlertEvent, observation: AlertObservation) {
        insert(event)
        insertObservation(observation)
    }

    @Transaction
    suspend fun updateWithObservation(event: AlertEvent, observation: AlertObservation) {
        update(event)
        insertObservation(observation)
    }

    @Insert
    suspend fun insertAttempt(attempt: DeliveryAttempt)

    @Query("SELECT * FROM delivery_attempts ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeAttempts(limit: Int): Flow<List<DeliveryAttempt>>

    @Query("SELECT * FROM evidence ORDER BY kind, provenance")
    fun observeEvidence(): Flow<List<EvidenceRecord>>

    @Query("SELECT * FROM evidence WHERE kind = :kind AND provenance = :provenance")
    suspend fun findEvidence(kind: EvidenceKind, provenance: EvidenceProvenance): EvidenceRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvidence(record: EvidenceRecord)

    @Query("SELECT COUNT(*) FROM seen_cell_broadcast_rows WHERE rowKey = :rowKey")
    suspend fun seenCount(rowKey: String): Int

    @Query("SELECT COUNT(*) FROM seen_cell_broadcast_rows")
    suspend fun seenTotal(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(row: SeenCellBroadcastRow)

    @Query("SELECT * FROM trigger_commands WHERE eventId = :eventId")
    suspend fun findCommand(eventId: String): TriggerCommand?

    @Query("SELECT * FROM trigger_commands ORDER BY alertReceivedAtMs DESC, createdAtMs DESC LIMIT 1")
    suspend fun latestCommand(): TriggerCommand?

    @Query("SELECT * FROM trigger_commands WHERE status IN ('PENDING', 'SENT') ORDER BY alertReceivedAtMs DESC, createdAtMs DESC LIMIT 1")
    suspend fun latestEffectiveCommand(): TriggerCommand?

    @Query("SELECT * FROM trigger_commands WHERE status = 'PENDING' ORDER BY alertReceivedAtMs DESC, createdAtMs DESC LIMIT 1")
    suspend fun pendingCommand(): TriggerCommand?

    @Query("UPDATE trigger_commands SET status = 'SUPERSEDED' WHERE status = 'PENDING'")
    suspend fun supersedePendingCommands()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommand(command: TriggerCommand)

    @Update
    suspend fun updateCommand(command: TriggerCommand)

    @Query("SELECT * FROM trigger_commands ORDER BY alertReceivedAtMs DESC, createdAtMs DESC LIMIT :limit")
    fun observeCommands(limit: Int): Flow<List<TriggerCommand>>
}

@Dao
interface DiagnosticDao {
    @Insert
    suspend fun insert(entry: DiagnosticEntry)

    @Query("SELECT * FROM diagnostic_entries ORDER BY atMs DESC, id DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<DiagnosticEntry>>

    @Query("SELECT * FROM diagnostic_entries WHERE category = :category ORDER BY atMs DESC, id DESC LIMIT :limit")
    fun observeCategory(category: String, limit: Int): Flow<List<DiagnosticEntry>>

    @Query("SELECT * FROM diagnostic_entries ORDER BY atMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticEntry>

    @Query("DELETE FROM diagnostic_entries WHERE id NOT IN (SELECT id FROM diagnostic_entries ORDER BY atMs DESC, id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}

@Database(
    entities = [
        AlertEvent::class,
        AlertObservation::class,
        DeliveryAttempt::class,
        DiagnosticEntry::class,
        EvidenceRecord::class,
        SeenCellBroadcastRow::class,
        TriggerCommand::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alerts(): AlertDao
    abstract fun diagnostics(): DiagnosticDao

    companion object {
        const val NAME = "cellalerthook-alerts.db"

        private val ADD_TRIGGER_COMMANDS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trigger_commands` (`eventId` TEXT NOT NULL, `action` TEXT NOT NULL, " +
                        "`alertReceivedAtMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `nextAttemptAtMs` INTEGER NOT NULL, `sentAtMs` INTEGER, " +
                        "`lastHttpStatus` INTEGER, `lastError` TEXT, PRIMARY KEY(`eventId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_commands_status` ON `trigger_commands` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_commands_alertReceivedAtMs` ON `trigger_commands` (`alertReceivedAtMs`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(ADD_TRIGGER_COMMANDS)
                .build()
    }
}
