package app.cellalerthook.domain

import app.cellalerthook.config.ReceiverConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset

class TriggerScheduleTest {
    private val schedule = ReceiverConfig(scheduleEnabled = true, scheduleDays = setOf(7, 1, 2, 3, 4), scheduleStartMinute = 420, scheduleEndMinute = 900).triggerSettings().schedule

    /** 1 November 2026 is a Sunday. */
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(ZoneOffset.ofHours(3))

    @Test
    fun `the test week is Sunday to Thursday, 07 00 to 15 00`() {
        val days = DayOfWeek.entries.filter { schedule.isOpen(at(1, 10).plusDays(it.value.toLong())) }
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), schedule.days)
        assertEquals(5, days.size)
        assertFalse(schedule.isOpen(at(6, 10)))
        assertFalse(schedule.isOpen(at(7, 10)))
    }

    @Test
    fun `the start minute is open and the end minute is closed`() {
        assertFalse(schedule.isOpen(at(2, 6, 59)))
        assertTrue(schedule.isOpen(at(2, 7)))
        assertTrue(schedule.isOpen(at(2, 14, 59)))
        assertFalse(schedule.isOpen(at(2, 15)))
    }

    @Test
    fun `switched off, it is always open`() {
        assertTrue(schedule.copy(enabled = false).isOpen(at(6, 3)))
    }

    @Test
    fun `the next opening is later today before the hours`() {
        assertEquals(at(2, 7), schedule.nextOpen(at(2, 6)))
    }

    @Test
    fun `the next opening is tomorrow after the hours`() {
        assertEquals(at(3, 7), schedule.nextOpen(at(2, 16)))
    }

    @Test
    fun `the next opening skips the weekend`() {
        assertEquals(at(8, 7), schedule.nextOpen(at(5, 16)))
    }

    @Test
    fun `with no days there is no next opening`() {
        assertNull(schedule.copy(days = emptySet()).nextOpen(at(2, 6)))
    }

    @Test
    fun `times are read and written in 24 hour form`() {
        assertEquals(450, TriggerSchedule.parseMinutes(" 7:30 "))
        assertEquals("07:30", TriggerSchedule.formatMinutes(450))
        listOf("24:00", "7", "7:60", "seven").forEach { assertNull(it, TriggerSchedule.parseMinutes(it)) }
    }
}
