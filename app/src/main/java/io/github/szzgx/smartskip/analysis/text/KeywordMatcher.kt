package io.github.szzgx.smartskip.analysis.text

/**
 * Matches normalised UI text against [KeywordRules].
 *
 * ## Why matches go through here rather than straight to a `Set`
 *
 * Keyword lists are written the way a human would write them (`"跳过广告"`, `"Skip Ad"`).
 * The text coming off a screen may contain the same phrase with whitespace inserted to
 * defeat matching (`"跳过 广告"`, `"Skip  Ad"`), or in full-width or styled characters.
 * Rather than maintain a second, pre-normalised copy of every list — which would silently
 * drift from the first — both sides are pushed through [TextNormalizer] at comparison
 * time.
 *
 * The lists are small (tens of entries) and this runs a few dozen times per frame, so the
 * cost is irrelevant next to the correctness gain.
 */
object KeywordMatcher {

    /**
     * @return true when the whole of [text] equals one of [keywords] once both are
     *   normalised.
     */
    fun exactMatch(text: String, keywords: Set<String>): Boolean =
        findExactMatch(text, keywords) != null

    /** @return the keyword that matched exactly, or `null`. */
    fun findExactMatch(text: String, keywords: Set<String>): String? {
        if (text.isEmpty()) return null
        val probe = TextNormalizer.matchingForm(text)
        if (probe.isEmpty()) return null
        return keywords.firstOrNull { TextNormalizer.matchingForm(it) == probe }
    }

    /**
     * @return true when [text] contains one of [keywords].
     */
    fun containsAny(text: String, keywords: Collection<String>): Boolean =
        findContained(text, keywords) != null

    /** @return the first keyword contained in [text], or `null`. */
    fun findContained(text: String, keywords: Collection<String>): String? {
        if (text.isEmpty()) return null
        val probe = TextNormalizer.matchingForm(text)
        if (probe.isEmpty()) return null
        return keywords.firstOrNull { keyword ->
            val needle = TextNormalizer.matchingForm(keyword)
            needle.isNotEmpty() && needle in probe
        }
    }

    /**
     * @return the matching token from [tokens] that appears in [text], matched
     *   case-insensitively as a substring. Used for resource-id fragments and
     *   view-class names, which are never whitespace-separated phrases.
     */
    fun findToken(text: String?, tokens: Collection<String>): String? {
        if (text.isNullOrEmpty()) return null
        val probe = text.lowercase()
        return tokens.firstOrNull { it.isNotEmpty() && it in probe }
    }

    /** @return true when any token in [tokens] appears in [text]. */
    fun containsToken(text: String?, tokens: Collection<String>): Boolean =
        findToken(text, tokens) != null
}
