package app.cellalerthook.domain

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Active days and hours, in the phone's local time. This limits only the on URL. The off URL
 * is always allowed, or a threat that outlasts the active hours would leave the target system on.
 */
data class TriggerSchedule(
    val enabled: Boolean,
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
) {
    fun isOpen(at: ZonedDateTime): Boolean {
        if (!enabled) return true
        val minute = at.hour * 60 + at.minute
        return at.dayOfWeek in days && minute >= startMinute && minute < endMinute
    }

    /** The next time the active hours start after [at], or null if no day is selected. */
    fun nextOpen(at: ZonedDateTime): ZonedDateTime? = (0..7L)
        .map { at.toLocalDate().plusDays(it) }
        .filter { it.dayOfWeek in days }
        .map { it.atTime(startMinute / 60, startMinute % 60).atZone(at.zone) }
        .firstOrNull { it.isAfter(at) }

    companion object {
        private val time = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

        /** Minutes since midnight from text like 07:30, or null if it is not a time. */
        fun parseMinutes(text: String): Int? =
            time.find(text.trim())?.destructured?.let { (hour, minute) -> hour.toInt() * 60 + minute.toInt() }

        fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
