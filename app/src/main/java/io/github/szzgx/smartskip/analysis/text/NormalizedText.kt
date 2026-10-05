package io.github.szzgx.smartskip.analysis.text

/**
 * The result of normalising a string for keyword comparison.
 *
 * @property canonical whitespace collapsed to single spaces between words. Used for
 *   storage and display.
 * @property compacted all whitespace removed. Used for CJK matching, where spaces are not
 *   word separators and their insertion is a common way to defeat keyword matching
 *   (`"跳 过"`, `"跳过 广告"`).
 * @property containsCjk whether the text contains CJK characters, which decides whether
 *   [compacted] is the meaningful form to match against.
 */
data class NormalizedText(
    val canonical: String,
    val compacted: String,
    val containsCjk: Boolean,
) {
    val isEmpty: Boolean get() = canonical.isEmpty()

    /**
     * The form to compare against a keyword set.
     *
     * For CJK text this is the whitespace-free form, because a space inserted into a
     * Chinese phrase carries no meaning and is a known evasion. For text containing Latin
     * letters the spaced form is kept, because there a space is a real word boundary and
     * removing it would merge distinct words.
     */
    val matching: String get() = if (containsCjk) compacted else canonical

    companion object {
        val EMPTY = NormalizedText(canonical = "", compacted = "", containsCjk = false)
    }
}
