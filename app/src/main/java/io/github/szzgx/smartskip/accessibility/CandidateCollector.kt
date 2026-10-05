package io.github.szzgx.smartskip.accessibility

import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.text.KeywordMatcher
import io.github.szzgx.smartskip.analysis.text.KeywordRules
import io.github.szzgx.smartskip.analysis.text.TextNormalizer

/**
 * Turns captured node summaries into [CandidateContext]s for the scoring engine.
 *
 * ## What counts as a candidate
 *
 * Only nodes with *some* skip-shaped evidence are assessed: skip wording in the text, a
 * skip phrase in the `contentDescription`, a skip-view-shaped `resource-id`, or a close
 * affordance. Everything else in the tree is used as context.
 *
 * This is the design doc's phase-1 filter, and it is deliberately recall-oriented while
 * the scoring engine is precision-oriented. Letting a few extra nodes through costs a
 * little CPU; the scoring and veto layers are what stop them being tapped.
 *
 * An editable, password or checkable node is never turned into a candidate at all. A
 * password field in particular has no business being reasoned about, and excluding it at
 * the entrance is a cheaper and more obvious guarantee than a veto further down.
 */
object CandidateCollector {

    /**
     * Ceiling on candidates assessed per frame. A tree full of the character "×" could
     * otherwise produce hundreds of assessments on every event.
     */
    private const val MAX_CANDIDATES = 24

    /**
     * Smallest side length, in pixels, for a candidate. Below roughly 16dp a node is a
     * decoration or a glyph rather than a control, and tapping it is a guess.
     */
    private const val MIN_SIDE_PX = 18

    private val CLOSE_GLYPHS = setOf("×", "x", "✕", "✖", "⨯", "╳", "✗", "✘", "close", "关闭")

    /**
     * @param analysis the captured frame.
     * @param history per-window observation memory, supplied by the service.
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
            CandidateContextFactory.fromNode(analysis, node, history)?.let { results.add(it) }
        }
        return results
    }

    /**
     * @return true when [node] carries any evidence worth scoring.
     */
    fun looksSkipShaped(node: NodeSummary): Boolean {
        if (node.isEditable || node.isPassword || node.isCheckable) return false
        if (node.width < MIN_SIDE_PX || node.height < MIN_SIDE_PX) return false

        val text = TextNormalizer.normalize(node.text)
        if (text.isNotEmpty()) {
            if (KeywordMatcher.exactMatch(text, KeywordRules.SKIP_EXACT)) return true
            if (text in CLOSE_GLYPHS) return true
            if (KeywordMatcher.containsAny(text, KeywordRules.SKIP_CONTAINS)) return true
            if (KeywordMatcher.exactMatch(text, KeywordRules.CLOSE_EXACT)) return true
        }

        val description = TextNormalizer.normalize(node.contentDescription)
        if (description.isNotEmpty()) {
            if (KeywordMatcher.exactMatch(description, KeywordRules.SKIP_EXACT)) return true
            if (KeywordMatcher.containsAny(description, KeywordRules.SKIP_CONTAINS)) return true
        }

        return KeywordMatcher.containsToken(node.viewIdResourceName, KeywordRules.SKIP_ID_HINTS)
    }

    /**
     * The structural fingerprint, matching [CandidateContext.structureSignature] so that
     * live candidates and stored rules agree. Also needed *before* a context exists, to
     * look up history for a node.
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
}

/**
 * Builds a [CandidateContext] from a captured node.
 *
 * Extracted so that the live engine and the inspector construct contexts through exactly
 * the same code. If they diverged, the inspector would show the user a decision the
 * engine never makes, which is worse than having no inspector at all.
 */
object CandidateContextFactory {

    /**
     * @param history per-window observation memory; a fresh instance is used when scoring
     *   a one-off capture such as the inspector's.
     */
    fun fromNode(
        analysis: WindowAnalysis,
        node: NodeSummary,
        history: SignatureHistory = SignatureHistory(analysis.capturedAtMs),
    ): CandidateContext? {
        if (analysis.screenWidth <= 0 || analysis.screenHeight <= 0) return null

        val signature = CandidateCollector.signatureOf(node)

        return CandidateContext(
            packageName = analysis.packageName.orEmpty(),
            activityName = analysis.activityName,
            nodeIndex = node.nodeIndex,
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
     * Concatenated text of the node's children and siblings.
     *
     * The countdown chip and the offer copy are usually *near* the skip button rather than
     * inside it, so this neighbourhood is where the two most useful negative signals live.
     */
    private fun neighbourhoodText(analysis: WindowAnalysis, node: NodeSummary): String {
        val sb = StringBuilder(128)

        fun append(from: NodeSummary) {
            if (sb.length >= MAX_NEIGHBOURHOOD_CHARS) return
            if (from.text.isNotEmpty()) sb.append(from.text).append(' ')
            if (from.contentDescription.isNotEmpty()) sb.append(from.contentDescription).append(' ')
        }

        node.childIndices.forEach { index ->
            analysis.nodes.getOrNull(index)?.let { append(it) }
        }

        val parent = analysis.nodes.getOrNull(node.parentIndex)
        parent?.childIndices?.forEach { index ->
            if (index != node.nodeIndex) analysis.nodes.getOrNull(index)?.let { append(it) }
        }

        return sb.toString().take(MAX_NEIGHBOURHOOD_CHARS)
    }

    private const val MAX_NEIGHBOURHOOD_CHARS = 400
}

/**
 * Per-window memory of how a control has behaved, keyed by structural signature.
 *
 * This is the source of the "size is stable" and "was ever clickable" features. It is
 * scoped to one window on purpose: carrying geometry observations across app launches
 * would let a coincidence from yesterday influence a decision today.
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
     * How many frames this signature has been observed.
     *
     * The signature already contains bucketed bounds, so a repeated signature *is* a
     * repeated size; no separate area comparison is needed.
     */
    fun sameSizeSamples(signature: String): Int = observations[signature]?.sameSizeCount ?: 0

    fun clickabilityObserved(signature: String): Boolean = observations[signature]?.everClickable ?: false

    fun lifetimeMs(signature: String, nowMs: Long): Long {
        val first = observations[signature]?.firstSeenMs ?: windowStartedAtMs
        return (nowMs - first).coerceAtLeast(0L)
    }

    /** Drops stale observations, bounding memory on a long-lived window. */
    fun prune(nowMs: Long, keepMs: Long = 8_000L) {
        observations.entries.removeAll { nowMs - it.value.firstSeenMs > keepMs }
    }
}
