package io.github.szzgx.smartskip.accessibility

import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.text.KeywordRules
import io.github.szzgx.smartskip.analysis.text.TextNormalizer

/**
 * Turns captured node summaries into [CandidateContext]s for the scoring engine,
 * and produces the frame-level context the engine also needs.
 *
 * ## What counts as a candidate
 *
 * Only nodes with *some* skip-shaped evidence are assessed: skip wording in the
 * text, a skip phrase in the `contentDescription`, a skip-view-shaped `resource-id`,
 * or a close affordance. Everything else in the tree is used as context.
 *
 * This is the design doc's phase-1 filter, and it is intentionally recall-oriented
 * while the scoring engine is precision-oriented. Letting a few extra nodes through
 * costs a little CPU; the scoring and veto layers are what keep them from being
 * tapped.
 *
 * A password field is never even turned into a candidate. There is no reason for
 * SmartSkip to reason about one, and excluding it at the entrance is a cheaper
 * guarantee than a veto further down.
 */
object CandidateCollector {

    /**
     * Ceiling on candidates assessed per frame. A pathological tree full of the
     * character "×" could otherwise produce hundreds of assessments on every event.
     */
    private const val MAX_CANDIDATES = 24

    /**
     * Smallest side length, in pixels, for a candidate. Below roughly 16dp a node is
     * a decoration or an icon glyph rather than a control, and tapping it is a guess.
     */
    private const val MIN_SIDE_PX = 18

    private val CLOSE_GLYPHS = setOf("×", "x", "✕", "✖", "⨯", "╳", "✗", "✘", "close", "关闭")

    /**
     * @param analysis the captured frame.
     * @param history a lookup for structural observations gathered during this
     *   window's lifetime, supplied by the service.
     */
    fun collect(
        analysis: WindowAnalysis,
        history: SignatureHistory,
    ): List<CandidateContext> {
        if (analysis.nodes.isEmpty()) return emptyList()

        val results = ArrayList<CandidateContext>(8)

        for (node in analysis.nodes) {
            if (results.size >= MAX_CANDIDATES) break
            if (!looksSkipShaped(node)) continue

            val context = buildContext(analysis, node, history) ?: continue
            results.add(context)
        }

        return results
    }

    /**
     * @return true when [node] carries any evidence worth scoring.
     */
    private fun looksSkipShaped(node: NodeSummary): Boolean {
        if (node.isEditable || node.isPassword || node.isCheckable) return false
        if (node.width < MIN_SIDE_PX || node.height < MIN_SIDE_PX) return false

        val text = TextNormalizer.normalize(node.text)
        val description = TextNormalizer.normalize(node.contentDescription)

        if (text.isNotEmpty()) {
            if (text in KeywordRules.SKIP_EXACT) return true
            if (KeywordRules.SKIP_CONTAINS.any { it in text }) return true
            if (KeywordRules.CLOSE_EXACT.any { it == text }) return true
            if (text in CLOSE_GLYPHS) return true
        }

        if (description.isNotEmpty()) {
            if (description in KeywordRules.SKIP_EXACT) return true
            if (KeywordRules.SKIP_CONTAINS.any { it in description }) return true
        }

        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.isNotEmpty() && KeywordRules.SKIP_ID_HINTS.any { it in id }) return true

        return false
    }

    private fun buildContext(
        analysis: WindowAnalysis,
        node: NodeSummary,
        history: SignatureHistory,
    ): CandidateContext? {
        if (analysis.screenWidth <= 0 || analysis.screenHeight <= 0) return null

        val signature = signatureOf(node)

        return CandidateContext(
            packageName = analysis.packageName.orEmpty(),
            activityName = analysis.activityName,
            depth = node.depth,
            className = node.className,
            viewIdResourceName = node.viewIdResourceName,
            ownText = TextNormalizer.normalize(node.text),
            ownContentDescription = TextNormalizer.normalize(node.contentDescription),
            nodeTextLen = node.text.length,
            boundsLeft = node.boundsLeft,
            boundsTop = node.boundsTop,
            boundsRight = node.boundsRight,
            boundsBottom = node.boundsBottom,
            screenWidth = analysis.screenWidth,
            screenHeight = analysis.screenHeight,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isVisibleToUser = node.isVisibleToUser,
            isImportantForAccessibility = node.isImportantForAccessibility,
            clickableAncestorDepth = node.clickableAncestorDepth,
            ancestorText = node.ancestorTexts.joinToString(" ").take(600),
            ancestorViewIds = node.ancestorIds,
            ancestorClasses = node.ancestorClasses,
            siblingText = neighbourhoodText(analysis, node),
            childCount = node.childIndices.size,
            leavesUnderNode = node.leavesUnderNode,
            sameSizeSamples = history.sameSizeSamples(signature),
            nodeLifetimeMs = history.lifetimeMs(signature, analysis.capturedAtMs),
            clickabilityEverObserved = history.clickabilityObserved(signature) || node.isClickable,
            isCountdownSiblingOnNode = false,
            isCountdownNearby = false,
        )
    }

    /**
     * Concatenated text of the candidate's children, siblings and grandchildren.
     *
     * The countdown chip and the offer copy are usually *near* the skip button rather
     * than inside it, so this neighbourhood is where the two most useful negative
     * signals live.
     */
    private fun neighbourhoodText(analysis: WindowAnalysis, node: NodeSummary): String {
        val sb = StringBuilder(128)

        fun append(from: NodeSummary) {
            if (sb.length >= MAX_NEIGHBOURHOOD_CHARS) return
            if (from.text.isNotEmpty()) {
                sb.append(from.text).append(' ')
            }
            if (from.contentDescription.isNotEmpty()) {
                sb.append(from.contentDescription).append(' ')
            }
        }

        // Children first: closest to the control.
        node.childIndices.forEach { index ->
            analysis.nodes.getOrNull(index)?.let { append(it) }
        }

        // Then siblings.
        val parent = analysis.nodes.getOrNull(node.parentIndex)
        parent?.childIndices?.forEach { index ->
            if (index != node.nodeIndex) analysis.nodes.getOrNull(index)?.let { append(it) }
        }

        return sb.toString().take(MAX_NEIGHBOURHOOD_CHARS)
    }

    /**
     * The structural fingerprint, matching the formula in
     * [CandidateContext.structureSignature] so that live candidates and stored rules
     * agree. Kept here as well because the service needs it *before* a context exists,
     * to look up history.
     */
    fun signatureOf(node: NodeSummary): String {
        val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
        val cls = node.className.substringAfterLast('.').lowercase()
        val l = node.boundsLeft / 8
        val t = node.boundsTop / 8
        val r = node.boundsRight / 8
        val b = node.boundsBottom / 8
        return "$id|$cls|$l,$t,$r,$b|d${node.depth}"
    }

    private const val MAX_NEIGHBOURHOOD_CHARS = 400
}

/**
 * Per-window memory of how a control has behaved, keyed by structural signature.
 *
 * This is the source of the "size is stable" and "was ever clickable" features. It
 * is scoped to one window on purpose: carrying geometry observations across app
 * launches would let a coincidence from yesterday influence a decision today.
 */
class SignatureHistory(private val windowStartedAtMs: Long) {

    private data class Observation(
        var firstSeenMs: Long,
        var sameSizeCount: Int,
        var everClickable: Boolean,
    )

    private val observations = mutableMapOf<String, Observation>()

    /** Records that a control with this signature was seen in this frame. */
    fun observe(signature: String, isClickable: Boolean, nowMs: Long) {
        val existing = observations[signature]
        if (existing == null) {
            observations[signature] = Observation(
                firstSeenMs = nowMs,
                sameSizeCount = 1,
                everClickable = isClickable,
            )
        } else {
            existing.sameSizeCount += 1
            if (isClickable) existing.everClickable = true
        }
    }

    /**
     * How many frames this signature has been observed with the same geometry.
     *
     * The key deliberately excludes the area, because a signature already contains
     * bucketed bounds; a repeated signature *is* a repeated size.
     */
    fun sameSizeSamples(signature: String): Int = observations[signature]?.sameSizeCount ?: 0

    fun clickabilityObserved(signature: String): Boolean = observations[signature]?.everClickable ?: false

    fun lifetimeMs(signature: String, nowMs: Long): Long {
        val first = observations[signature]?.firstSeenMs ?: windowStartedAtMs
        return (nowMs - first).coerceAtLeast(0L)
    }

    /** Drops observations that have not been seen for a while, bounding memory. */
    fun prune(nowMs: Long, keepMs: Long = 8_000L) {
        observations.entries.removeAll { nowMs - it.value.firstSeenMs > keepMs }
    }
}

/**
 * Per-window bookkeeping the decision pipeline needs: when the window appeared, how
 * many frames it has produced, and the last countdown value seen in it.
 *
 * @property windowStartedAtMs when the current window first appeared.
 * @property foregroundSinceMs when the current package came to the foreground.
 * @property frameCount how many frames this window has produced.
 * @property lastCountdownSeconds the most recent countdown value observed.
 * @property countdownDecreased whether the countdown was seen shrinking.
 */
data class WindowState(
    var windowStartedAtMs: Long,
    var foregroundSinceMs: Long,
    var frameCount: Int,
    var lastCountdownSeconds: Int?,
    var countdownDecreased: Boolean,
)
