package io.github.szzgx.smartskip.analysis.text

import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.accessibility.NodeSummary
import io.github.szzgx.smartskip.accessibility.WindowAnalysis

/**
 * Turns a [CandidateContext] into [TextEvidence].
 *
 * This analyser implements the design doc's first-stage rule layer (§5.1) and,
 * critically, its warning: a keyword is evidence, never a verdict. Nothing here
 * returns "click" — it returns a bag of signals, including the negative ones that
 * expose bait.
 */
object TextSignalAnalyzer {

    /**
     * How many ancestors and siblings contribute context. Deeper walks start
     * costing real time on large trees, and a skip button's surrounding chrome is
     * always local.
     */
    private const val CONTEXT_SCAN_CHARS = 400

    /**
     * Analyses a live candidate.
     *
     * Delegates to the shared implementation so that the live engine, the candidate
     * filter and the inspector all share exactly one definition of "what does this text
     * say". Two copies would drift, and a drifting copy here means the inspector showing
     * the user a decision the engine never makes.
     */
    fun analyze(ctx: CandidateContext): TextEvidence {
        val evidence = analyzeSynthetic(
            ownText = ctx.ownText,
            ownContentDescription = ctx.ownContentDescription,
            viewIdResourceName = ctx.viewIdResourceName,
            ancestorText = ctx.ancestorText,
            ancestorViewIds = ctx.ancestorViewIds,
            siblingText = ctx.siblingText,
        )

        // Live contexts carry two extra observations that a freshly captured node does
        // not: whether a countdown was seen on or near the node in an earlier frame.
        val signals = evidence.signals.toMutableSet()
        if (ctx.isCountdownSiblingOnNode) signals += TextSignal.COUNTDOWN_IN_NODE
        if (ctx.isCountdownNearby) signals += TextSignal.COUNTDOWN_NEARBY

        return evidence.copy(signals = signals)
    }

    /**
     * Analyses a captured node without building a full [CandidateContext].
     *
     * Used by the inspector to label nodes before scoring, and by the candidate filter.
     * It shares one definition of "what the text says" with the live path, so the
     * inspector cannot disagree with the engine about a node.
     */
    fun analyzeSynthetic(node: NodeSummary, analysis: WindowAnalysis): TextEvidence =
        analyzeSynthetic(
            ownText = TextNormalizer.normalize(node.text),
            ownContentDescription = TextNormalizer.normalize(node.contentDescription),
            viewIdResourceName = node.viewIdResourceName,
            ancestorText = node.ancestorTexts.joinToString(" ").take(CONTEXT_SCAN_CHARS),
            ancestorViewIds = node.ancestorIds,
            siblingText = siblingTextOf(node, analysis),
        )

    /**
     * The shared implementation, expressed in terms of already-normalised strings so
     * that both entry points cannot drift apart.
     */
    fun analyzeSynthetic(
        ownText: String,
        ownContentDescription: String,
        viewIdResourceName: String?,
        ancestorText: String,
        ancestorViewIds: List<String>,
        siblingText: String,
    ): TextEvidence {
        val signals = linkedSetOf<TextSignal>()
        val phrases = linkedSetOf<String>()

        val fullId = viewIdResourceName?.lowercase().orEmpty()

        if (ownText.isNotEmpty()) {
            /*
             * All keyword comparisons go through KeywordMatcher, which normalises both
             * sides. That is what makes `"跳 过"` and `"跳过 广告"` match, and it removes
             * the need to keep a second pre-normalised copy of every keyword list.
             */
            when {
                KeywordMatcher.exactMatch(ownText, KeywordRules.SKIP_EXACT) ->
                    signals += TextSignal.SKIP_EXACT

                KeywordMatcher.containsAny(ownText, KeywordRules.SKIP_CONTAINS) ->
                    signals += TextSignal.SKIP_CONTAINS_ONLY
            }
            if (signals.any { it == TextSignal.SKIP_EXACT || it == TextSignal.SKIP_CONTAINS_ONLY }) {
                phrases += ownText
            }
            if (KeywordMatcher.exactMatch(ownText, KeywordRules.CLOSE_EXACT)) {
                signals += TextSignal.CLOSE
                phrases += ownText
            }
            if (KeywordMatcher.exactMatch(ownText, KeywordRules.CREATIVE_HINTS)) {
                signals += TextSignal.CREATIVE_TEXT
                phrases += ownText
            }
            if (KeywordMatcher.exactMatch(ownText, KeywordRules.STRONG_DANGER)) {
                signals += TextSignal.STRONG_DANGER_ON_NODE
                phrases += ownText
            }
            if (KeywordMatcher.exactMatch(ownText, KeywordRules.INDUCEMENT)) {
                signals += TextSignal.INDUCEMENT
                phrases += ownText
            }
            if (KeywordMatcher.exactMatch(ownText, KeywordRules.AD_DISCLOSURE)) {
                signals += TextSignal.AD_DISCLOSURE_ON_ANCESTOR
                phrases += ownText
            }
            if (containsCountdown(ownText)) signals += TextSignal.COUNTDOWN_IN_NODE
        }

        if (ownContentDescription.isNotEmpty()) {
            if (KeywordMatcher.exactMatch(ownContentDescription, KeywordRules.SKIP_EXACT) ||
                KeywordMatcher.containsAny(ownContentDescription, KeywordRules.SKIP_CONTAINS)
            ) {
                signals += TextSignal.SKIP_IN_CONTENT_DESC
                phrases += ownContentDescription
            }
            if (KeywordMatcher.exactMatch(ownContentDescription, KeywordRules.STRONG_DANGER)) {
                signals += TextSignal.STRONG_DANGER_ON_NODE
                phrases += ownContentDescription
            }
        }

        val skipId = KeywordMatcher.containsToken(fullId, KeywordRules.SKIP_ID_HINTS)
        if (skipId) {
            signals += TextSignal.SKIP_IN_VIEW_ID
            phrases += fullId
        }
        val adContainerId = KeywordMatcher.containsToken(fullId, KeywordRules.AD_CONTAINER_ID_HINTS)

        if (adContainerId) {
            signals += TextSignal.AD_DISCLOSURE_IN_WINDOW
        }

        if (ancestorText.isNotEmpty()) {
            if (KeywordMatcher.containsAny(ancestorText, KeywordRules.AD_DISCLOSURE)) {
                signals += TextSignal.AD_DISCLOSURE_ON_ANCESTOR
            }
            if (KeywordMatcher.containsAny(ancestorText, KeywordRules.STRONG_DANGER)) {
                signals += TextSignal.STRONG_DANGER_NEARBY
            }
        }
        if (ancestorViewIds.any { KeywordMatcher.containsToken(it, KeywordRules.AD_CONTAINER_ID_HINTS) }) {
            signals += TextSignal.AD_DISCLOSURE_ON_ANCESTOR
        }

        if (siblingText.isNotEmpty()) {
            if (KeywordMatcher.containsAny(siblingText, KeywordRules.STRONG_DANGER)) {
                signals += TextSignal.STRONG_DANGER_NEARBY
            }
            if (KeywordMatcher.containsAny(siblingText, KeywordRules.INDUCEMENT)) {
                signals += TextSignal.INDUCEMENT
            }
            if (containsCountdown(siblingText)) signals += TextSignal.COUNTDOWN_NEARBY
            if (KeywordMatcher.containsAny(siblingText, KeywordRules.AD_DISCLOSURE)) {
                signals += TextSignal.AD_DISCLOSURE_IN_WINDOW
            }
        }

        return TextEvidence(
            signals = signals,
            matchedPhrases = phrases,
            ownLabel = ownText.ifEmpty { ownContentDescription },
            isAdContainerId = adContainerId,
            isSkipId = skipId,
            isPaymentId = KeywordMatcher.containsToken(fullId, KeywordRules.PAYMENT_ID_HINTS),
        )
    }

    private fun siblingTextOf(node: NodeSummary, analysis: WindowAnalysis): String {
        val sb = StringBuilder(128)
        node.childIndices.forEach { index ->
            analysis.nodes.getOrNull(index)?.let { child ->
                if (sb.length < CONTEXT_SCAN_CHARS) sb.append(child.text).append(' ')
            }
        }
        val parent = analysis.nodes.getOrNull(node.parentIndex)
        parent?.childIndices?.forEach { index ->
            if (index != node.nodeIndex && sb.length < CONTEXT_SCAN_CHARS) {
                analysis.nodes.getOrNull(index)?.let { sb.append(it.text).append(' ') }
            }
        }
        return sb.toString().take(CONTEXT_SCAN_CHARS)
    }

    /**
     * @return true when [text] is, or contains, a plausible ad countdown.
     *
     * Deliberately strict: a bare `"3"` only counts when the whole string is that number,
     * because a bare digit matches unread badges and version strings all over a normal UI.
     */
    fun containsCountdown(text: String): Boolean {
        if (text.isEmpty()) return false
        val candidate = text.trim()
        if (candidate.length > 12) return false
        return KeywordRules.COUNTDOWN_PATTERNS.any { it.matches(candidate) }
    }

    /**
     * Extracts the remaining seconds from a countdown label, if present.
     *
     * @return the number of seconds, or `null`.
     */
    fun parseCountdownSeconds(text: String): Int? {
        val candidate = text.trim()
        if (candidate.isEmpty() || candidate.length > 12) return null
        for (pattern in KeywordRules.COUNTDOWN_PATTERNS) {
            val m = pattern.find(candidate) ?: continue
            val value = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            // A countdown is seconds; anything above 60 is some other number that
            // happened to match a permissive pattern.
            if (value in 0..60) return value
        }
        return null
    }
}
