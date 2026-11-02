package app.cellalerthook.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `the same PIN and salt give the same hash`() {
        assertArrayEquals(PinHash.of("482913", salt), PinHash.of("482913", salt))
    }

    @Test
    fun `a different PIN or salt gives a different hash`() {
        assertFalse(PinHash.of("482913", salt).contentEquals(PinHash.of("482914", salt)))
        assertFalse(PinHash.of("482913", salt).contentEquals(PinHash.of("482913", PinHash.newSalt())))
    }

    @Test
    fun `Arabic and Western digits are the same PIN`() {
        assertArrayEquals(PinHash.of("482913", salt), PinHash.of("٤٨٢٩١٣", salt))
        assertTrue(PinHash.isAllowed("٤٨٢٩١٣"))
    }

    @Test
    fun `a PIN must be 6 to 12 digits`() {
        assertTrue(PinHash.isAllowed("123456"))
        assertTrue(PinHash.isAllowed("123456789012"))
        assertFalse(PinHash.isAllowed("12345"))
        assertFalse(PinHash.isAllowed("1234567890123"))
        assertFalse(PinHash.isAllowed("12345a"))
        assertFalse(PinHash.isAllowed(""))
    }

    @Test
    fun `the first wrong tries cost nothing, then the wait doubles up to an hour`() {
        assertEquals(0L, PinThrottle.waitMs(0))
        assertEquals(0L, PinThrottle.waitMs(4))
        assertEquals(30_000L, PinThrottle.waitMs(5))
        assertEquals(60_000L, PinThrottle.waitMs(6))
        assertEquals(3_600_000L, PinThrottle.waitMs(12))
        assertEquals(3_600_000L, PinThrottle.waitMs(5_000))
    }

    @Test
    fun `a new install holds no certificate and can never call a URL until armed`() {
        val defaults = ReceiverConfig()
        assertEquals("", defaults.pinnedCaPem)
        assertEquals(false, defaults.scheduleEnabled)
        assertEquals(false, defaults.triggerArmed)
    }
}
