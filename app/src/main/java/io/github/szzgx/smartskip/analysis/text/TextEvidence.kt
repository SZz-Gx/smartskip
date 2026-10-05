package io.github.szzgx.smartskip.analysis.text

/**
 * One piece of text evidence found on a node or in its immediate context.
 *
 * The scoring engine consumes these as booleans-with-provenance rather than as a
 * single "is this a skip button" verdict, which is what lets the UI explain
 * *why* a decision was made.
 */
enum class TextSignal {
    /** The node's own visible text is exactly a skip phrase (`跳过` / `Skip`). */
    SKIP_EXACT,

    /** The node's text merely contains a skip phrase (`跳过即可领取奖励`). */
    SKIP_CONTAINS_ONLY,

    /** The node's `contentDescription` is a skip phrase (icon-only buttons). */
    SKIP_IN_CONTENT_DESC,

    /** The node's resource-id looks like an ad SDK's skip view. */
    SKIP_IN_VIEW_ID,

    /** The node is a close affordance (`×`, `关闭`). */
    CLOSE,

    /** Advertising disclosure present somewhere in the window. */
    AD_DISCLOSURE_IN_WINDOW,

    /** Advertising disclosure present on an ancestor container of the node. */
    AD_DISCLOSURE_ON_ANCESTOR,

    /** Offer wording (`立即下载`, `查看详情`) suggesting the node is creative. */
    CREATIVE_TEXT,

    /** Serious-action wording (`立即支付`, `立即下载`) on the node itself. */
    STRONG_DANGER_ON_NODE,

    /** Serious-action wording somewhere in the node's immediate context. */
    STRONG_DANGER_NEARBY,

    /** Inducement wording (`免费领取`, `红包`) next to skip wording. */
    INDUCEMENT,

    /** A countdown appears in the node's own text. */
    COUNTDOWN_IN_NODE,

    /** A countdown appears in a small node near the candidate. */
    COUNTDOWN_NEARBY,
}

/**
 * The result of analysing a node and its immediate surroundings.
 *
 * @property signals the distinct pieces of evidence found.
 * @property matchedPhrases the normalised phrases that produced the signals.
 *   Retained in memory only, for the inspector and the explanation panel. It is
 *   never written to the database — see [TextObservation] for the persisted form.
 * @property ownLabel the node's normalised own text, for display.
 * @property isAdContainerId whether the node's id matches an ad-container pattern.
 */
data class TextEvidence(
    val signals: Set<TextSignal>,
    val matchedPhrases: Set<String>,
    val ownLabel: String,
    val isAdContainerId: Boolean,
    val isSkipId: Boolean,
    val isPaymentId: Boolean,
) {
    operator fun contains(signal: TextSignal): Boolean = signal in signals

    val hasSkipWording: Boolean
        get() = signals.any {
            it == TextSignal.SKIP_EXACT ||
                it == TextSignal.SKIP_CONTAINS_ONLY ||
                it == TextSignal.SKIP_IN_CONTENT_DESC ||
                it == TextSignal.SKIP_IN_VIEW_ID
        }

    val hasStrongDanger: Boolean
        get() = TextSignal.STRONG_DANGER_ON_NODE in signals

    companion object {
        val EMPTY = TextEvidence(
            signals = emptySet(),
            matchedPhrases = emptySet(),
            ownLabel = "",
            isAdContainerId = false,
            isSkipId = false,
            isPaymentId = false,
        )
    }
}

/**
 * The privacy-safe, persisted projection of text evidence.
 *
 * Design doc §18 forbids persisting user content and asks for "text features as a
 * class or hash". Rather than hashing the label — a hash of a short, guessable
 * string such as a nickname is reversible by dictionary attack — this records
 * only the *classification*. A skip button stays recognisable tomorrow; the words
 * it happened to display are not written to disk at all.
 */
data class TextObservation(
    /** True when the persisted rule should be treated as a skip-shaped control. */
    val skipShaped: Boolean,
    /** True when the control was on an explicit ad surface. */
    val onAdSurface: Boolean,
    /** True when the control's id matched an ad SDK skip-view pattern. */
    val skipIdShaped: Boolean,
) {
    val isEmpty: Boolean get() = !skipShaped && !skipIdShaped

    companion object {
        val NONE = TextObservation(skipShaped = false, onAdSurface = false, skipIdShaped = false)

        fun from(evidence: TextEvidence): TextObservation = TextObservation(
            skipShaped = evidence.hasSkipWording,
            onAdSurface = TextSignal.AD_DISCLOSURE_IN_WINDOW in evidence ||
                TextSignal.AD_DISCLOSURE_ON_ANCESTOR in evidence ||
                evidence.isAdContainerId,
            skipIdShaped = evidence.isSkipId,
        )
    }
}
