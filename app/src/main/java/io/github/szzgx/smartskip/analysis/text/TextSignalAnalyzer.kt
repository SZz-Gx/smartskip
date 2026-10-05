package io.github.szzgx.smartskip.analysis.text

import io.github.szzgx.smartskip.analysis.CandidateContext

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

    fun analyze(ctx: CandidateContext): TextEvidence {
        val signals = linkedSetOf<TextSignal>()
        val phrases = linkedSetOf<String>()

        val ownText = ctx.ownText
        val ownDesc = ctx.ownContentDescription
        val viewId = ctx.viewIdResourceName?.lowercase()?.substringAfterLast('/').orEmpty()
        val fullId = ctx.viewIdResourceName?.lowercase().orEmpty()

        // ---------------------------------------------------------------- text

        if (ownText.isNotEmpty()) {
            when {
                ownText in KeywordRules.SKIP_EXACT -> {
                    signals += TextSignal.SKIP_EXACT
                    phrases += ownText
                }

                KeywordRules.SKIP_CONTAINS.any { it in ownText } -> {
                    // Contains skip wording but is not exactly it. Weak evidence,
                    // and a frequent signature of reward-bait buttons.
                    signals += TextSignal.SKIP_CONTAINS_ONLY
                    phrases += ownText
                }
            }
            if (ownText in KeywordRules.CLOSE_EXACT) {
                signals += TextSignal.CLOSE
                phrases += ownText
            }
            if (ownText in KeywordRules.CREATIVE_HINTS) {
                signals += TextSignal.CREATIVE_TEXT
                phrases += ownText
            }
            if (ownText in KeywordRules.STRONG_DANGER) {
                signals += TextSignal.STRONG_DANGER_ON_NODE
                phrases += ownText
            }
            if (ownText in KeywordRules.INDUCEMENT) {
                signals += TextSignal.INDUCEMENT
                phrases += ownText
            }
            if (ownText in KeywordRules.AD_DISCLOSURE) {
                signals += TextSignal.AD_DISCLOSURE_ON_ANCESTOR
                phrases += ownText
            }
            if (containsCountdown(ownText)) {
                signals += TextSignal.COUNTDOWN_IN_NODE
            }
        }

        // ------------------------------------------------- contentDescription
        // Icon-only skip buttons carry no text at all; the label often lives only
        // here. This is the single most commonly missed case.

        if (ownDesc.isNotEmpty()) {
            if (ownDesc in KeywordRules.SKIP_EXACT || KeywordRules.SKIP_CONTAINS.any { it in ownDesc }) {
                signals += TextSignal.SKIP_IN_CONTENT_DESC
                phrases += ownDesc
            }
            if (ownDesc in KeywordRules.STRONG_DANGER) {
                signals += TextSignal.STRONG_DANGER_ON_NODE
                phrases += ownDesc
            }
        }

        // ------------------------------------------------------------ view id
        // A resource-id is the hardest signal for an ad SDK to fake accidentally:
        // it is chosen by the SDK author, not by the creative.

        val skipId = KeywordRules.SKIP_ID_HINTS.any { it in fullId }
        if (skipId) {
            signals += TextSignal.SKIP_IN_VIEW_ID
            phrases += fullId
        }
        val adContainerId = KeywordRules.AD_CONTAINER_ID_HINTS.any { it in fullId }
        val paymentId = KeywordRules.PAYMENT_ID_HINTS.any { it in fullId }

        // ----------------------------------------------------- ancestor scope
        // An ad SDK usually marks the container, not the button.

        if (ctx.ancestorText.isNotEmpty()) {
            val anc = ctx.ancestorText.take(CONTEXT_SCAN_CHARS)
            if (KeywordRules.AD_DISCLOSURE.any { it in anc }) {
                signals += TextSignal.AD_DISCLOSURE_ON_ANCESTOR
            }
            if (KeywordRules.STRONG_DANGER.any { it in anc }) {
                signals += TextSignal.STRONG_DANGER_NEARBY
            }
        }
        if (ctx.ancestorViewIds.any { id -> KeywordRules.AD_CONTAINER_ID_HINTS.any { it in id.lowercase() } }) {
            signals += TextSignal.AD_DISCLOSURE_ON_ANCESTOR
        }

        // ---------------------------------------------------- sibling scope
        // The "10s" chip is very often a sibling of the skip button rather than
        // part of it.

        if (ctx.siblingText.isNotEmpty()) {
            val sib = ctx.siblingText.take(CONTEXT_SCAN_CHARS)
            if (KeywordRules.STRONG_DANGER.any { it in sib }) {
                signals += TextSignal.STRONG_DANGER_NEARBY
            }
            if (KeywordRules.INDUCEMENT.any { it in sib }) {
                signals += TextSignal.INDUCEMENT
            }
            if (containsCountdown(sib)) {
                signals += TextSignal.COUNTDOWN_NEARBY
            }
            if (KeywordRules.AD_DISCLOSURE.any { it in sib }) {
                signals += TextSignal.AD_DISCLOSURE_IN_WINDOW
            }
        }

        if (ctx.isCountdownSiblingOnNode) signals += TextSignal.COUNTDOWN_IN_NODE
        if (ctx.isCountdownNearby) signals += TextSignal.COUNTDOWN_NEARBY

        if (KeywordRules.AD_DISCLOSURE.any { it in viewId }) {
            signals += TextSignal.AD_DISCLOSURE_IN_WINDOW
        }

        return TextEvidence(
            signals = signals,
            matchedPhrases = phrases,
            ownLabel = ownText.ifEmpty { ownDesc },
            isAdContainerId = adContainerId,
            isSkipId = skipId,
            isPaymentId = paymentId,
        )
    }

    /**
     * @return true when [text] is, or contains, a plausible ad countdown.
     *
     * Deliberately strict: a bare `"3"` only counts when the whole string is that
     * number, because a bare digit matches things like unread-badge counts and
     * version strings all over a normal UI.
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
