package app.cellalerthook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTextTest {
    @Test
    fun `sanitizer keeps Arabic, diacritics and bidi marks`() {
        val text = "‏تَنْبِيه طوارئ‏"
        assertEquals(text.trim(), TextSanitizer.clean(text, 100))
    }

    @Test
    fun `sanitizer strips control characters and normalizes line ends`() {
        assertEquals("a\nb\nc", TextSanitizer.clean("a\r\nb\rc\u0000\u0007", 100))
    }

    @Test
    fun `sanitizer returns null for nothing useful`() {
        assertNull(TextSanitizer.clean(null, 100))
        assertNull(TextSanitizer.clean(" \r\n ", 100))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val cut = TextSanitizer.clean("ab😀cd", 3)!!
        assertEquals("ab", cut)
    }

    @Test
    fun `fingerprint ignores formatting differences`() {
        assertEquals(AlertFingerprint.of("تَنْبِيه  طوارئ"), AlertFingerprint.of("‏تنبيه\nطوارئ "))
        assertEquals(AlertFingerprint.of("Alert  NOW"), AlertFingerprint.of("alert now"))
    }

    @Test
    fun `fingerprint separates different messages`() {
        assertFalse(AlertFingerprint.of("Evacuate north") == AlertFingerprint.of("Evacuate south"))
    }

    @Test
    fun `containment needs enough text to mean anything`() {
        assertFalse(AlertFingerprint.sameMessage("Alert", "Alert: evacuate the north building now"))
        assertTrue(
            AlertFingerprint.sameMessage(
                "evacuate the north building now",
                "Alert: evacuate the north building now, follow staff",
            ),
        )
    }

    @Test
    fun `language comes from the broadcast header when present`() {
        val result = LanguageDetector.detect("Hello there", "ar")
        assertEquals("ar", result.language)
        assertTrue(result.fromHeader)
    }

    @Test
    fun `malformed header falls back to the script`() {
        val result = LanguageDetector.detect("تنبيه طوارئ", "\u0000\u0000")
        assertEquals(LanguageDetector.ARABIC, result.language)
        assertFalse(result.fromHeader)
    }

    @Test
    fun `script fallback handles every case`() {
        assertEquals(LanguageDetector.ARABIC, LanguageDetector.detect("تنبيه طوارئ من الدفاع المدني", null).language)
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.detect("Emergency alert", null).language)
        assertEquals(LanguageDetector.MIXED, LanguageDetector.detect("تنبيه طوارئ Emergency", null).language)
        assertEquals(LanguageDetector.UNKNOWN, LanguageDetector.detect("12345 !!!", null).language)
    }
}
