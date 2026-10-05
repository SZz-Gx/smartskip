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
            /*
             * Detects the fake-skip pattern inside a single label, which exact matching misses.
             *
             * Found by running the adversarial harness on a real device: a control labelled
             * "跳过领取" scored 22 and was refused only because the number was low. It was not
             * vetoed, because the inducement check required the whole label to equal an entry in
             * INDUCEMENT, and "跳过领取" does not equal "领取". The rejection was therefore
             * accidental and would not survive a bait button whose label happened to score higher —
             * and the first-sight threshold that also refused it is exactly what rule learning is
             * designed to lower.
             *
             * Two mechanisms, because one is not enough:
             *
             *  * a known inducement word anywhere in the label; and
             *  * the structural signature [KeywordMatcher.looksLikeBaitLabel] — skip wording
             *    followed by further Chinese words, which is what bait always looks like because its
             *    real purpose is the reward. The second mechanism is what makes this robust instead
             *    of dependent on a word list that can never be completed.
             */
            val labelledInducement = KeywordMatcher.containsAny(ownText, KeywordRules.INDUCEMENT)
            if (labelledInducement || KeywordMatcher.looksLikeBaitLabel(ownText)) {
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
            /*
             * Bait detection for the content-description branch, which is not an afterthought: an
             * icon-only skip button carries its label here and nowhere else, so this is where a fake
             * one hides its "跳过领取".
             *
             * Found by the adversarial harness on a device. A control with
             * `contentDescription='跳过领取'` produced only `[SKIP_IN_CONTENT_DESC]` and no veto,
             * because the bait check had been written for the `text` branch alone. The label was
             * visible to the engine and simply not examined.
             */
            if (KeywordMatcher.containsAny(ownContentDescription, KeywordRules.INDUCEMENT) ||
                KeywordMatcher.looksLikeBaitLabel(ownContentDescription)
            ) {
                signals += TextSignal.INDUCEMENT
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
     * @return the number of seconds, or `null`.
     *
     * ## The bare-digit range is deliberately narrow
     *
     * A countdown that is *labelled* as seconds is accepted up to [MAX_LABELLED_SECONDS]. A bare
     * number is held to the much smaller [MAX_BARE_SECONDS], because the bare-digit pattern has no
     * semantic anchor at all.
     *
     * This was calibrated on a real device, where a live capture of Zhihu's feed produced
     * `desc='赞同58' text='58' id=statusTv` and `desc='收藏42' text='42'` — like, comment and
     * favourite counters, all classified as countdowns. Splash-ad countdowns run to roughly fifteen
     * seconds; social counters routinely exceed that. Narrowing the bare range removes the false
     * positives while still accepting every countdown form an ad actually uses (`"5"`, `"跳过 3"`).
     */
    fun parseCountdownSeconds(text: String): Int? {
        if (text.isBlank() || text.length > 12) return null

        /*
         * Matched against the same normalised form the keyword layer uses, not the raw text.
         *
         * The raw text is not lower-cased, so `"Skip 3"` — with a capital S, which is how an English
         * ad labels its control — did not match a pattern written in lower case. Chinese labels were
         * unaffected, which is exactly the kind of asymmetry that stays hidden until a Latin UI is
         * tested.
         */
        val candidate = TextNormalizer.matchingForm(text)
        if (candidate.isEmpty()) return null

        KeywordRules.COUNTDOWN_PATTERNS.forEachIndexed { index, pattern ->
            val m = pattern.find(candidate) ?: return@forEachIndexed
            val value = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEachIndexed
            val limit = if (index == KeywordRules.BARE_DIGIT_PATTERN_INDEX) {
                MAX_BARE_SECONDS
            } else {
                MAX_LABELLED_SECONDS
            }
            if (value in 0..limit) return value
        }
        return null
    }

    /** Upper bound for a countdown that says so (`"31秒"`, `"31s"`, `"剩余31秒"`). */
    private const val MAX_LABELLED_SECONDS = 60

    /**
     * Upper bound for a bare number. Deliberately small: a splash-ad countdown is short, and a
     * larger value is far more likely to be an engagement counter.
     */
    private const val MAX_BARE_SECONDS = 15
}
