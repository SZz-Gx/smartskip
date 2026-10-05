package io.github.szzgx.smartskip.accessibility

import android.accessibilityservice.AccessibilityService
import io.github.szzgx.smartskip.analysis.Assessment
import io.github.szzgx.smartskip.analysis.AutoSkipMode
import io.github.szzgx.smartskip.analysis.WindowContext
import io.github.szzgx.smartskip.analysis.assessment.AssessmentScorer
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome
import io.github.szzgx.smartskip.analysis.risk.WindowRiskProbe
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.analysis.text.TextSignalAnalyzer
import io.github.szzgx.smartskip.decision.DecisionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One node as the inspector shows it: the raw facts plus everything SmartSkip made of them.
 *
 * Flattened rather than nested, because the inspector renders a list and the UI wants a
 * `LazyColumn` key. [depth] and [childCount] carry the hierarchy, and [indent] is the
 * ready-made prefix.
 *
 * @property text the node's visible text.
 * @property textSignals the text evidence classes found on this node.
 * @property isCandidate whether this node was skip-shaped enough to be scored.
 * @property score/band/effectiveThreshold the assessment, when this node was scored.
 * @property wouldClick whether the engine would act, given the threshold.
 * @property vetoes hard blockers that applied.
 * @property contributions `code=points`, largest magnitude first.
 */
data class InspectorNode(
    val index: Int,
    val depth: Int,
    val text: String,
    val contentDescription: String,
    val viewId: String?,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val editable: Boolean,
    val visibleToUser: Boolean,
    val importantForAccessibility: Boolean,
    val childCount: Int,
    val textSignals: List<String>,
    val isCandidate: Boolean,
    val score: Int?,
    val band: String?,
    val effectiveThreshold: Int?,
    val wouldClick: Boolean,
    val vetoes: List<String>,
    val contributions: List<String>,
) {
    /** Ready-made indentation for a flat list, capped so deep trees stay readable. */
    val indent: String get() = "  ".repeat(depth.coerceAtMost(MAX_INDENT))

    private companion object {
        const val MAX_INDENT = 12
    }
}

/**
 * A complete inspector capture.
 *
 * @property packageName/activityName what was in the foreground.
 * @property screenWidth/screenHeight the window size at capture time.
 * @property candidateCount how many nodes produced skip-shaped evidence.
 * @property scored whether the capture includes full scoring, or only the raw tree.
 * @property truncated true when the tree walk hit its node budget, so the capture is
 *   incomplete and conclusions drawn from it should be treated as provisional.
 * @property adContextAvailable whether an advertising container was found.
 * @property paymentContextAvailable whether a payment or credential surface was found.
 */
data class InspectorSnapshot(
    val packageName: String,
    val activityName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAtMs: Long,
    val candidateCount: Int,
    val scored: Boolean,
    val truncated: Boolean,
    val adContextAvailable: Boolean,
    val paymentContextAvailable: Boolean,
    val nodes: List<InspectorNode>,
) {
    companion object {
        val EMPTY = InspectorSnapshot(
            packageName = "",
            activityName = null,
            screenWidth = 0,
            screenHeight = 0,
            capturedAtMs = 0L,
            candidateCount = 0,
            scored = false,
            truncated = false,
            adContextAvailable = false,
            paymentContextAvailable = false,
            nodes = emptyList(),
        )
    }
}

/**
 * State holder for the inspector.
 *
 * The service writes, the UI reads. A process-wide object rather than a database table, because
 * a capture is a debugging artefact of *this* moment and persisting every frame to SQLite would
 * be absurd.
 *
 * ## Privacy note
 *
 * This is the one place in SmartSkip that retains screen text in memory for longer than a
 * frame. That is inherent to what an inspector *is* — it shows you the tree — and it is why
 * [clear] exists and why the inspector screen offers it. Nothing is ever written to disk from
 * here.
 */
object UiDumpProvider {

    private val _snapshot = MutableStateFlow(InspectorSnapshot.EMPTY)

    /** The most recent capture, for Compose to observe. */
    val snapshot: StateFlow<InspectorSnapshot> = _snapshot.asStateFlow()

    /**
     * Whether per-frame capture is wanted.
     *
     * Building a snapshot means running text analysis over every node and materialising the
     * whole tree as strings. Doing that on every accessibility frame, for a screen nobody may be
     * looking at, is pure waste on a hot path — so it is off by default and turned on only while
     * the inspector screen is actually open.
     */
    @Volatile
    private var liveCaptureEnabled: Boolean = false

    /** Called by the inspector when it becomes visible. */
    fun setLiveCaptureEnabled(enabled: Boolean) {
        liveCaptureEnabled = enabled
    }

    /** Drops the retained capture, so the UI can discard screen text on demand. */
    fun clear() {
        _snapshot.value = InspectorSnapshot.EMPTY
    }

    /**
     * Publishes a raw, unscored capture of the frame the service just assessed.
     *
     * Does nothing unless [setLiveCaptureEnabled] was called with `true`.
     */
    fun publish(analysis: WindowAnalysis) {
        if (!liveCaptureEnabled) return
        _snapshot.value = build(analysis, scoreCandidates = false)
    }

    /**
     * Captures the current window through the service and publishes a fully scored snapshot.
     *
     * This is the "Run inspection" action. It has to go through the service because
     * `rootInActiveWindow` is only reachable from an `AccessibilityService`.
     *
     * @return true when a frame was captured.
     */
    fun captureThroughService(service: AccessibilityService): Boolean {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        val packageName = runCatching { root.packageName?.toString() }.getOrNull()

        val analysis = runCatching {
            AccessibilityTreeCollector.capture(
                root = root,
                packageName = packageName,
                activityName = null,
                nowMs = System.currentTimeMillis(),
            )
        }.getOrNull() ?: return false

        _snapshot.value = build(analysis, scoreCandidates = true)
        return true
    }

    private fun build(analysis: WindowAnalysis, scoreCandidates: Boolean): InspectorSnapshot {
        /*
         * The candidate filter is delegated to `CandidateCollector`, so the inspector shows
         * exactly the set the engine would consider. Re-implementing the filter here is the
         * classic way an inspector starts lying to its user.
         */
        val candidateIndices = analysis.nodes
            .filter { CandidateCollector.looksSkipShaped(it) }
            .map { it.nodeIndex }

        val probe = if (candidateIndices.isEmpty()) {
            AccessibilityTreeCollector.ProbeResult.NONE
        } else {
            AccessibilityTreeCollector.probeRisk(analysis, candidateIndices)
        }

        val nodes = analysis.nodes.map { node ->
            val text = TextSignalAnalyzer.analyzeSynthetic(node, analysis)
            val isCandidate = node.nodeIndex in candidateIndices

            val scored = if (isCandidate && scoreCandidates) scoreOne(analysis, node, probe, candidateIndices.size) else null

            InspectorNode(
                index = node.nodeIndex,
                depth = node.depth,
                text = node.text,
                contentDescription = node.contentDescription,
                viewId = node.viewIdResourceName,
                className = node.className,
                bounds = "${node.boundsLeft},${node.boundsTop},${node.boundsRight},${node.boundsBottom}",
                clickable = node.isClickable,
                editable = node.isEditable,
                visibleToUser = node.isVisibleToUser,
                importantForAccessibility = node.isImportantForAccessibility,
                childCount = node.childIndices.size,
                textSignals = text.signals.map { it.name },
                isCandidate = isCandidate,
                score = scored?.assessment?.score,
                band = scored?.assessment?.band?.name,
                effectiveThreshold = scored?.threshold,
                wouldClick = scored?.let { it.assessment.score >= it.threshold && !it.assessment.isVetoed } == true,
                vetoes = scored?.assessment?.vetoes?.map { it.name } ?: emptyList(),
                contributions = scored?.assessment?.contributions
                    ?.sortedByDescending { kotlin.math.abs(it.points) }
                    ?.map { "${it.code}=${it.points}" }
                    ?: emptyList(),
            )
        }

        return InspectorSnapshot(
            packageName = analysis.packageName.orEmpty(),
            activityName = analysis.activityName,
            screenWidth = analysis.screenWidth,
            screenHeight = analysis.screenHeight,
            capturedAtMs = analysis.capturedAtMs,
            candidateCount = candidateIndices.size,
            scored = scoreCandidates,
            truncated = analysis.truncated,
            adContextAvailable = probe.hasAdDisclosureAnywhere,
            paymentContextAvailable = probe.hasPaymentContext,
            nodes = nodes,
        )
    }

    private data class Scored(val assessment: Assessment, val threshold: Int)

    /**
     * Scores one candidate the way the live engine would, using the same scorer and the same
     * candidate threshold.
     *
     * The history is [HistoryEvidence.UNKNOWN] because a one-off capture has no history to
     * consult; the resulting threshold is therefore the first-sight one, which is the honest
     * answer for "what would SmartSkip do right now, cold?".
     */
    private fun scoreOne(
        analysis: WindowAnalysis,
        node: NodeSummary,
        probe: AccessibilityTreeCollector.ProbeResult,
        candidateCount: Int,
    ): Scored? {
        val ctx = CandidateContextFactory.fromNode(analysis, node) ?: return null

        // The capture timestamp is used for both window and package start, so the timing
        // evidence reports "just appeared" rather than a stale age from an earlier frame.
        val window = WindowContext(
            packageName = analysis.packageName,
            activityName = analysis.activityName,
            rootClassName = analysis.rootClassName,
            packageForegroundSinceMs = analysis.capturedAtMs,
            windowAppearedAtMs = analysis.capturedAtMs,
            nowMs = analysis.capturedAtMs,
            distinctFrameCount = 1,
            previousCountdownSeconds = null,
            isCountdownShrinking = false,
            hasAdDisclosureAnywhere = probe.hasAdDisclosureAnywhere,
            hasPaymentContext = probe.hasPaymentContext,
            hasDownloadOrStoreContext = probe.hasDownloadOrStoreContext,
            hasNestedClickableDescendant = probe.hasNestedClickableDescendant,
        )

        val assessment = AssessmentScorer.assessFull(
            ctx = ctx,
            window = window,
            probe = AccessibilityTreeCollector.probeForSingleCandidate(analysis, node.nodeIndex)
                .toWindowRiskProbe(candidateCount),
            history = HistoryEvidence.UNKNOWN,
            dangerousPriorOutcomes = emptySet<DangerousOutcome>(),
        )

        val thresholds = DecisionEngine.thresholdsFor(AutoSkipMode.STANDARD)
        val threshold = if (assessment.isFirstSight) {
            DecisionEngine.FIRST_SIGHT_OBSERVATION_THRESHOLD
        } else {
            thresholds.clickThreshold
        }

        return Scored(assessment, threshold)
    }
}

/** Adapts the collector's raw probe to the analysis package's type. */
private fun AccessibilityTreeCollector.ProbeResult.toWindowRiskProbe(candidateCount: Int): WindowRiskProbe =
    WindowRiskProbe(
        hasAdCreativeOverlap = hasAdCreativeOverlap,
        hasPaymentContext = hasPaymentContext,
        hasDownloadOrStoreContext = hasDownloadOrStoreContext,
        hasNestedClickableDescendant = hasNestedClickableDescendant,
        candidateIsCentered = candidateIsCentered,
        windowHasCountdownAnywhere = false,
        distinctClickableCandidates = candidateCount,
    )
