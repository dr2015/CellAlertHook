package app.cellalerthook.detection

import android.database.Cursor
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.CellBroadcastMetadata
import app.cellalerthook.domain.OriginTrust
import app.cellalerthook.domain.RawAlertObservation

/** One row of the system provider, independent of Cursor so the mapping tests on the JVM. */
interface CellBroadcastRow {
    fun string(column: String): String?
    fun long(column: String): Long?
}

class CursorCellBroadcastRow(private val cursor: Cursor) : CellBroadcastRow {
    override fun string(column: String): String? {
        val index = cursor.getColumnIndex(column)
        return if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
    }

    override fun long(column: String): Long? {
        val index = cursor.getColumnIndex(column)
        return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
    }
}

object CellBroadcastRowMapper {
    const val COLUMN_ID = "_id"
    const val COLUMN_RECEIVED_TIME = "received_time"
    const val SORT_NEWEST_FIRST = "$COLUMN_RECEIVED_TIME DESC"

    /** Stable across restarts, and survives a provider database reset reusing row ids. */
    fun rowKey(row: CellBroadcastRow): String? {
        val id = row.long(COLUMN_ID) ?: return null
        val received = row.long(COLUMN_RECEIVED_TIME) ?: return null
        return "$id:$received"
    }

    /** Null when the row lacks the identifiers every Cell Broadcast message must carry. */
    fun toObservation(
        row: CellBroadcastRow,
        providerPackage: String?,
        observedAtMs: Long,
        catchUp: Boolean,
    ): RawAlertObservation? {
        val rowId = row.long(COLUMN_ID) ?: return null
        val messageId = row.long("service_category")?.toInt() ?: return null
        val serial = row.long("serial_number")?.toInt() ?: return null
        val received = row.long(COLUMN_RECEIVED_TIME) ?: return null
        val metadata = CellBroadcastMetadata(
            rowId = rowId,
            messageId = messageId,
            serialNumber = serial,
            plmn = row.string("plmn"),
            lac = row.long("lac")?.toInt(),
            cid = row.long("cid")?.toInt(),
            slotIndex = row.long("slot_index")?.toInt(),
            subscriptionId = row.long("sub_id")?.toInt(),
            receivedTimeMs = received,
            messageFormat = row.long("format")?.toInt(),
            messagePriority = row.long("priority")?.toInt(),
            dataCodingScheme = row.long("dcs")?.toInt(),
            cmasMessageClass = row.long("cmas_message_class")?.toInt(),
            cmasCategory = row.long("cmas_category")?.toInt(),
            cmasResponseType = row.long("cmas_response_type")?.toInt(),
            cmasSeverity = row.long("cmas_severity")?.toInt(),
            cmasUrgency = row.long("cmas_urgency")?.toInt(),
            cmasCertainty = row.long("cmas_certainty")?.toInt(),
            etwsWarningType = row.long("etws_warning_type")?.toInt(),
            displayed = row.long("message_displayed")?.let { it != 0L },
        )
        return RawAlertObservation(
            method = CaptureMethod.CELL_BROADCAST_PROVIDER,
            observedAtMs = observedAtMs,
            title = null,
            body = row.string("body"),
            originPackage = providerPackage,
            originClass = "CellBroadcastProvider",
            originTrust = OriginTrust.SYSTEM_IMAGE,
            languageHint = row.string("language"),
            cellBroadcast = metadata,
            catchUp = catchUp,
        )
    }
}
