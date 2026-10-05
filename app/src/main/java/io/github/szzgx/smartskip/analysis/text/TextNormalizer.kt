package io.github.szzgx.smartskip.analysis.text

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises user-facing strings so that keyword matching cannot be evaded by cosmetic
 * tricks.
 *
 * Ad SDKs routinely defeat naive `text == "跳过"` checks by injecting characters that
 * render identically but compare differently. This normaliser strips the tricks that are
 * cheap to defeat; the ones that remain are covered by structural evidence instead of by
 * text alone.
 *
 * Transformations, in order:
 *
 *  1. [Normalizer.NFKC] — folds compatibility characters, collapsing full-width ASCII
 *     (`Ｓｋｉｐ`) and many look-alike forms onto their canonical equivalents.
 *  2. Remove zero-width and invisible formatting characters. The old anti-Root tools
 *     never had to deal with this, but `"跳\u200b过"` is a real evasion and is trivial to
 *     defeat.
 *  3. Fold the mathematically styled Latin alphabets (`𝐒𝐤𝐢𝐩`), which NFKC leaves alone.
 *  4. Collapse whitespace, and produce a whitespace-free variant. The whitespace-free form
 *     is what CJK text is matched against, because spaces are not word separators in
 *     Chinese and inserting one (`"跳 过"`) is a common evasion.
 *  5. Case-fold with [Locale.ROOT] so Turkish-style locale rules cannot change the result
 *     of matching "skip".
 *
 * Cyrillic and Greek homoglyph substitution (`"Ѕkip"`, `"ЅКІР"`) is deliberately NOT
 * attempted: collapsing homoglyphs would mangle legitimate text in those scripts, and a
 * homoglyph skip button is already suspicious enough to be caught by the structural and
 * positional evidence.
 */
object TextNormalizer {

    /**
     * Characters that carry no visual weight and are essentially never meaningful.
     *
     * Written as literal `\u` escapes in a raw string so that what the regex engine sees
     * is unambiguous. An earlier version wrote these as `"\\u200B"` inside a normal
     * Kotlin string, which produced the two-character sequence `\` `u` rather than the
     * character itself, and silently failed to strip anything.
     */
    private const val INVISIBLE_CHARS =
        "\u00AD" + // soft hyphen
            "\u200B\u200C\u200D\u200E\u200F" + // zero-width space .. right-to-left mark
            "\u2060\u2061\u2062\u2063\u2064" + // word joiner and invisible operators
            "\uFEFF" + // zero-width no-break space / BOM
            "\u180E" // Mongolian vowel separator

    private val INVISIBLE_REGEX = Regex("[$INVISIBLE_CHARS]")

    /**
     * Every kind of whitespace a UI can contain, including the ones `\s` misses on some
     * engines: no-break space, the CJK ideographic space, en/em and thin spaces.
     */
    private val WHITESPACE_REGEX = Regex(
        "[\\s\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+",
    )

    /** CJK ideographs plus the kana and Hangul blocks. */
    private val CJK_REGEX = Regex("[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\uAC00-\uD7AF]")

    /**
     * @return the canonical (space-separated) and compacted forms of [raw].
     */
    fun normalizeDetailed(raw: CharSequence?): NormalizedText {
        if (raw == null) return NormalizedText.EMPTY
        val trimmed = raw.toString().trim()
        if (trimmed.isEmpty()) return NormalizedText.EMPTY

        var s = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        s = INVISIBLE_REGEX.replace(s, "")
        s = foldMathAlphabet(s)
        s = WHITESPACE_REGEX.replace(s, " ").trim()
        s = s.lowercase(Locale.ROOT)

        val containsCjk = CJK_REGEX.containsMatchIn(s)
        val compacted = if (containsCjk) s.replace(" ", "") else s

        return NormalizedText(
            canonical = s,
            compacted = compacted,
            containsCjk = containsCjk,
        )
    }

    /**
     * @return the canonical normalised form of [raw], or `""` for null or blank input.
     */
    fun normalize(raw: CharSequence?): String = normalizeDetailed(raw).canonical

    /**
     * The form keyword sets are matched against. For CJK text this has no whitespace.
     */
    fun matchingForm(raw: CharSequence?): String = normalizeDetailed(raw).matching

    /**
     * Same as [normalize] but preserving original case, for display in the inspector.
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

    /**
     * A short, non-reversible digest of the normalised text.
     *
     * Retained for callers that need to recognise "the same label as before" without
     * storing it. Note that the persisted rule format deliberately does *not* use this:
     * a hash of a three-character label is trivially reversed, so rules store only the
     * text's classification. See [TextObservation].
     */
    fun digest(raw: CharSequence?): String {
        val norm = normalize(raw)
        if (norm.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(norm.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun foldMathAlphabet(input: String): String {
        // Fast path: most strings contain no styled characters, and this runs for every
        // node of every frame, so allocating a StringBuilder unconditionally is wasteful.
        var needsWork = false
        for (c in input) {
            if (c.code in 0x1D400..0x1D7FF || c in MATH_LETTERLIKE) {
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
     * Mathematically styled Latin alphabets (`U+1D400`..`U+1D7FF`) plus the letterlike
     * symbols that NFKC leaves on their own.
     */
    private val MATH_ALPHABET: Map<Char, Char> = buildMap {
        val upperStarts = intArrayOf(
            0x1D400, 0x1D434, 0x1D468, 0x1D49C, 0x1D4D0,
            0x1D504, 0x1D538, 0x1D56C, 0x1D5A0,
        )
        val lowerStarts = intArrayOf(
            0x1D41A, 0x1D44E, 0x1D482, 0x1D4B6, 0x1D4EA,
            0x1D51E, 0x1D552, 0x1D586, 0x1D5BA,
        )
        for (start in upperStarts) {
            for (i in 0 until 26) put((start + i).toChar(), ('A' + i))
        }
        for (start in lowerStarts) {
            for (i in 0 until 26) put((start + i).toChar(), ('a' + i))
        }
        put('\u212A', 'K') // Kelvin sign
        put('\u212B', 'A') // Angstrom sign
        put('\u2126', 'O') // Ohm sign
        put('\u0131', 'i') // dotless i
    }

    private val MATH_LETTERLIKE: Set<Char> = setOf('\u212A', '\u212B', '\u2126', '\u0131')
}
