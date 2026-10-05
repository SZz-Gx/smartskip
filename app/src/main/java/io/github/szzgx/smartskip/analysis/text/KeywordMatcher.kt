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
     * Matches "skip wording immediately followed by further Chinese words".
     *
     * ## Why a pattern rather than a bigger word list
     *
     * Enumerating inducement words is a losing game: 领取, 领奖, 领奖励, 赢好礼, 抽大奖, 天天领,
     * 限时福利 and so on without end. The *skip* vocabulary, by contrast, is small and closed.
     *
     * A genuine skip control says "跳过", "跳过广告" or "跳过此广告" — the phrase ends there. A fake
     * one has to keep talking, because its real purpose is the reward: "跳过领奖". So the shape
     * "skip immediately followed by more Chinese characters" identifies bait without needing to
     * know what the reward is called.
     *
     * ## How it is bounded, to avoid punishing normal UI
     *
     * Latin characters, digits, punctuation and whitespace are allowed to follow (so "跳过 Ad" and
     * "跳过 3" are not bait, and a countdown is handled elsewhere). A *run* of CJK characters
     * following the skip wording is what counts, and the lookbehind requires "跳过" to start a word,
     * so a sentence merely containing the word — "如何跳过某某步骤" — does not match.
     *
     * The remaining false-positive risk is a legitimate Chinese UI label that begins with "跳过"
     * and continues, such as a tutorial control "跳过引导". Marking that as bait refuses to click
     * it, which costs a missed skip and never a mis-click. That is the correct direction of error
     * for this project, and refusing on an ambiguous label is exactly what the scoring engine does
     * with everything else.
     */
    private val SKIP_FOLLOWED_BY_MORE_CJK = Regex("(?<![\\u4e00-\\u9fff])跳过(?![\\s\\dA-Za-z\\p{P}])[\\u4e00-\\u9fff]")

    /**
     * @return true when [text] looks like a skip control whose wording continues, which is the
     *   structural signature of a fake skip button. See [SKIP_FOLLOWED_BY_MORE_CJK].
     *
     * ## Established skip phrasings are exempt, and that exemption is load-bearing
     *
     * "跳过广告" and "跳过此广告" are in [KeywordRules.SKIP_EXACT] because they are how ordinary
     * splash ads label their own skip control. The structural pattern matches them too — "skipping"
     * is followed by more characters — so without this exemption the guard flags the two most
     * common names of the very button it exists to protect. That regression was caught by running
     * the pattern over a list of real skip labels, and it would have been silent: the app would
     * simply never skip anything whose button says "跳过广告".
     *
     * The distinction the pattern is meant to draw is therefore: for a label that is *exactly* a
     * known skip phrase, further wording is part of that phrase; for anything else, "跳过" followed
     * by more words is the fake-skip shape.
     */
    fun looksLikeBaitLabel(text: String): Boolean {
        if (text.isEmpty()) return false
        val probe = TextNormalizer.matchingForm(text)
        if (probe.isEmpty()) return false
        if (exactMatch(probe, KeywordRules.SKIP_EXACT)) return false
        return SKIP_FOLLOWED_BY_MORE_CJK.containsMatchIn(probe)
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
