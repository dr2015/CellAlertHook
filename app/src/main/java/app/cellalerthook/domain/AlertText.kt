package app.cellalerthook.domain

import java.security.MessageDigest
import java.text.Normalizer

object TextSanitizer {
    const val MAX_TITLE_LENGTH = 256

    /** 15 pages of 93 septets is 1395 characters; the extra room covers UI-added text. */
    const val MAX_BODY_LENGTH = 4000

    /**
     * Makes untrusted text safe to store while keeping the message itself intact.
     * Only C0 control characters go: Arabic, diacritics and bidi marks are preserved.
     */
    fun clean(raw: CharSequence?, maxLength: Int): String? {
        if (raw == null) return null
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            when {
                ch == '\r' -> {
                    val followedByNewline = index + 1 < raw.length && raw[index + 1] == '\n'
                    if (!followedByNewline) out.append('\n')
                }
                ch == '\n' || ch == '\t' -> out.append(ch)
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                else -> out.append(ch)
            }
            index += 1
        }
        val trimmed = out.toString().trim()
        if (trimmed.isEmpty()) return null
        return truncate(trimmed, maxLength)
    }

    private fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val cut = if (text[maxLength - 1].isHighSurrogate()) maxLength - 1 else maxLength
        return text.substring(0, cut)
    }
}

object AlertFingerprint {
    private const val MIN_CONTAINMENT_LENGTH = 24

    /** Comparison form only. The stored and transmitted message keeps its original text. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val out = StringBuilder(folded.length)
        var lastWasSpace = true
        for (ch in folded) {
            when {
                isArabicMark(ch) || Character.getType(ch) == Character.FORMAT.toInt() -> Unit
                ch.isWhitespace() -> {
                    if (!lastWasSpace) out.append(' ')
                    lastWasSpace = true
                }
                else -> {
                    out.append(ch)
                    lastWasSpace = false
                }
            }
        }
        return out.toString().trim()
    }

    fun of(text: String): String = sha256(normalize(text))

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * True when two observations carry the same words. Containment covers a UI that shows
     * a message plus extra lines, or a notification that shortens it.
     */
    fun sameMessage(first: String, second: String): Boolean {
        val a = normalize(first)
        val b = normalize(second)
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= MIN_CONTAINMENT_LENGTH && longer.contains(shorter)
    }

    private fun isArabicMark(ch: Char): Boolean =
        ch in 'ً'..'ٟ' || ch == 'ٰ' || ch == 'ـ'
}

object LanguageDetector {
    const val ARABIC = "ar"
    const val ENGLISH = "en"
    const val MIXED = "mixed"
    const val UNKNOWN = "unknown"

    private val languageCode = Regex("^[A-Za-z]{2,3}$")
    private const val DOMINANT_SHARE = 0.8

    data class Result(val language: String, val fromHeader: Boolean)

    /** Prefers the language in the broadcast header. Script counting is only a fallback. */
    fun detect(text: String, hint: String?): Result {
        val headerLanguage = hint?.trim()?.lowercase()
        if (headerLanguage != null && languageCode.matches(headerLanguage)) {
            return Result(headerLanguage, fromHeader = true)
        }
        return Result(fromScript(text), fromHeader = false)
    }

    private fun fromScript(text: String): String {
        var arabic = 0
        var latin = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.ARABIC,
                Character.UnicodeBlock.ARABIC_SUPPLEMENT,
                Character.UnicodeBlock.ARABIC_EXTENDED_A,
                Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
                Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B -> arabic += 1
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A -> latin += 1
                else -> Unit
            }
        }
        val total = arabic + latin
        if (total == 0) return UNKNOWN
        return when {
            arabic.toDouble() / total >= DOMINANT_SHARE -> ARABIC
            latin.toDouble() / total >= DOMINANT_SHARE -> ENGLISH
            else -> MIXED
        }
    }
}
