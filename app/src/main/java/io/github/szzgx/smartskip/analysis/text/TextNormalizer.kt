package io.github.szzgx.smartskip.analysis.text

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises user-facing strings so that keyword matching cannot be evaded by
 * cosmetic tricks.
 *
 * Ad SDKs routinely defeat naive `text == "跳过"` checks by injecting characters
 * that render identically but compare differently. This normaliser strips the
 * tricks that are cheap to defeat, and the ones that remain are covered by
 * additional evidence rather than by text alone.
 *
 * Transformations, in order:
 *
 *  1. [Normalizer.NFKC] — folds compatibility characters. This collapses
 *     full-width ASCII (`Ｓｋｉｐ`) and many look-alike forms onto their
 *     canonical equivalents.
 *  2. Remove zero-width and invisible formatting characters (`U+200B`..`U+200F`,
 *     `U+2060`..`U+2064`, `U+FEFF`, soft hyphen, word joiner). `"跳\u200b过"`
 *     is a common evasion.
 *  3. Replace a curated set of mathematically styled Latin letters (bold,
 *     italic, script, double-struck, ...) with plain ASCII. NFKC does *not*
 *     fold these, and `𝐒𝐤𝐢𝐩` is a real-world evasion.
 *  4. Collapse every run of whitespace (including NBSP, ideographic space and
 *     newlines) to a single ASCII space, then trim.
 *  5. Case-fold with [Locale.ROOT] so Turkish-style locale rules cannot change
 *     the result of matching "skip".
 *
 * Cyrillic/Greek homoglyph substitution ("Ѕkip", "ЅКІР") is deliberately NOT
 * attempted: collapsing homoglyphs would mangle legitimate text in those
 * scripts, and a homoglyph skip button is already suspicious enough to be caught
 * by the structural and positional evidence instead.
 */
object TextNormalizer {

    /** Characters that carry no visual weight and are almost never meaningful. */
    private const val INVISIBLE_CHARS =
        "\u00AD" + // soft hyphen
            "\u200B\u200C\u200D\u200E\u200F" + // zero-width space..RLM
            "\u2060\u2061\u2062\u2063\u2064" + // word joiner, invisible operators
            "\uFEFF" + // zero-width no-break space / BOM
            "\u180E" // Mongolian vowel separator

    private val INVISIBLE_REGEX = Regex("[$INVISIBLE_CHARS]")

    /** Whitespace that [Regex]'s `\s` does not cover by default in all cases. */
    private val WHITESPACE_REGEX = Regex("[\\s\\u00A0\\u3000\\u2000-\\u200A\\u202F]+")

    /**
     * Mathematically styled Latin alphabets (`U+1D400`..`U+1D7FF`) plus the
     * letterlike symbols that NFKC leaves alone. Each entry maps one styled
     * character to its ASCII base.
     */
    private val MATH_ALPHABET: Map<Char, Char> = buildMap {
        // Offsets of the four 26-letter runs inside the Mathematical Alphanumeric
        // Symbols block, relative to 'A' / 'a'.
        val upperStarts = intArrayOf(0x1D400, 0x1D434, 0x1D468, 0x1D49C, 0x1D4D0, 0x1D504, 0x1D538, 0x1D56C, 0x1D5A0)
        val lowerStarts = intArrayOf(0x1D41A, 0x1D44E, 0x1D482, 0x1D4B6, 0x1D4EA, 0x1D51E, 0x1D552, 0x1D586, 0x1D5BA)
        for (start in upperStarts) {
            for (i in 0 until 26) {
                put((start + i).toChar(), ('A' + i))
            }
        }
        for (start in lowerStarts) {
            for (i in 0 until 26) {
                put((start + i).toChar(), ('a' + i))
            }
        }
        // Letterlike look-alikes that NFKC does not fold onto ASCII.
        put('\u212A', 'K') // Kelvin sign
        put('\u212B', 'A') // Angstrom sign
        put('\u2126', 'O') // Ohm sign
        put('\u0131', 'i') // dotless i
    }

    /**
     * @return the normalised form of [raw], or `""` for null/blank input.
     */
    fun normalize(raw: CharSequence?): String {
        if (raw == null) return ""
        val trimmed = raw.toString().trim()
        if (trimmed.isEmpty()) return ""

        var s = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        s = INVISIBLE_REGEX.replace(s, "")
        s = foldMathAlphabet(s)
        s = WHITESPACE_REGEX.replace(s, " ")
        return s.trim().lowercase(Locale.ROOT)
    }

    /**
     * Same as [normalize] but preserves original case. Useful when the original
     * casing must be shown to the user in the inspector.
     */
    fun normalizePreservingCase(raw: CharSequence?): String {
        if (raw == null) return ""
        val trimmed = raw.toString().trim()
        if (trimmed.isEmpty()) return ""
        var s = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        s = INVISIBLE_REGEX.replace(s, "")
        s = foldMathAlphabet(s)
        return WHITESPACE_REGEX.replace(s, " ").trim()
    }

    private fun foldMathAlphabet(input: String): String {
        // Fast path: the overwhelming majority of strings contain no styled
        // characters at all, and allocating a StringBuilder per call is wasteful
        // because this runs for every node on every accessibility event.
        var needsWork = false
        for (c in input) {
            if (c.code in 0x1D400..0x1D7FF || MATH_ALPHABET.containsKey(c)) {
                needsWork = true
                break
            }
        }
        if (!needsWork) return input

        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(MATH_ALPHABET[c] ?: c)
        }
        return sb.toString()
    }

    /**
     * A SHA-256 digest of the normalised visible text, used where the raw label
     * must not be persisted (design doc §18: store text *classes* or hashes, not
     * user content). Only ever applied to the short label of a candidate control.
     */
    fun digest(raw: CharSequence?): String {
        val norm = normalize(raw)
        if (norm.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(norm.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
