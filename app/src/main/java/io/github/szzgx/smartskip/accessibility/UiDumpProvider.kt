package io.github.szzgx.smartskip.accessibility

import io.github.szzgx.smartskip.analysis.Assessment
import io.github.szzgx.smartskip.analysis.assessment.AssessmentScorer
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.analysis.text.TextSignalAnalyzer
import io.github.szzgx.smartskip.analysis.WindowContext

/**
 * One node as the inspector shows it: the raw facts plus everything SmartSkip made of
 * them.
 *
 * Flattened rather than nested because the inspector renders it as a list and exports it
 * as JSON, and both are simpler without a tree structure. The `depth` field carries the
 * hierarchy.
 *
 * @property text the node's visible text.
 * @property textSignals the text evidence classes found on this node.
 * @property isCandidate whether this node was skip-shaped enough to be scored.
 * @property score/stageScore/effectiveThreshold the assessment, when scored.
 * @property vetoes hard blockers that applied.
 * @property contributions `code=points` for every scoring contribution.
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
    val textSignals: List<String>,
    val isCandidate: Boolean,
    val score: Int?,
    val band: String?,
    val effectiveThreshold: Int?,
    val wouldClick: Boolean,
    val vetoes: List<String>,
    val contributions: List<String>,
)

/**
 * A complete inspector capture.
 *
 * @property packageName/activityName what was in the foreground.
 * @property screenWidth/screenHeight the window size at capture time.
 * @property candidateCount how many nodes produced skip-shaped evidence.
 * @property truncated true when the tree walk hit its node budget, so the capture is
 *   incomplete and conclusions drawn from it should be treated as provisional.
 * @property adContextAvailable whether an advertising marker was found, which is the
 *   single strongest window-level signal.
 * @property paymentContextAvailable whether a payment or credential surface was found.
 */
data class InspectorSnapshot(
    val packageName: String,
    val activityName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAtMs: Long,
    val candidateCount: Int,
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
 * The service writes; the UI reads. Kept as a process-wide object rather than routed
 * through the database, because an inspector capture is a debugging artefact of *this*
 * moment and writing every frame to SQLite would be absurd.
 *
 * Note that this holds onto text from the screen for as long as the process lives. That
 * is the whole point of an inspector — it is a developer tool — but it is the one place
 * in SmartSkip where screen text is retained, so it is documented here and surfaced in
 * the UI as such.
 */
object UiDumpProvider {

    private val _snapshot = kotlinx.coroutines.flow.MutableStateFlow(InspectorSnapshot.EMPTY)

    /** The most recent capture, for Compose to observe. */
    val snapshot: kotlinx.coroutines.flow.StateFlow<InspectorSnapshot> = _snapshot

    /** Clears the retained capture, so the UI can drop screen text on demand. */
    fun clear() {
        _snapshot.value = InspectorSnapshot.EMPTY
    }

    /** Publishes a raw capture, without scoring. */
    fun publish(analysis: WindowAnalysis) {
        _snapshot.value = build(analysis, scoreCandidates = false)
    }

    /**
     * Publishes a fully scored capture. Used by the **Run inspection** action, where the
     * user explicitly asked for the analysis and the extra cost is expected.
     */
    fun publishScored(analysis: WindowAnalysis) {
        _snapshot.value = build(analysis, scoreCandidates = true)
    }

    private fun build(analysis: WindowAnalysis, scoreCandidates: Boolean): InspectorSnapshot {
        /*
         * The candidate filter is delegated to `CandidateCollector`, so the inspector
         * shows exactly the set the engine would consider. Re-implementing the filter
         * here is the classic way an inspector starts lying to its user.
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

            var assessment: Assessment? = null
            var threshold: Int? = null
            if (isCandidate && scoreCandidates) {
                val ctx = CandidateContextFactory.fromNode(analysis, node)
                if (ctx != null) {
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
                    assessment = AssessmentScorer.assessFull(
                        ctx = ctx,
                        window = window,
                        probe = AccessibilityTreeCollector.probeForSingleCandidate(
                            analysis,
                            node.nodeIndex,
                        ).toWindowRiskProbe(candidateCount = candidateIndices.size),
                        history = HistoryEvidence.UNKNOWN,
                        dangerousPriorOutcomes = emptySet<DangerousOutcome>(),
                    )
                    threshold = io.github.szzgx.smartskip.decision.DecisionEngine
                        .thresholdsFor(io.github.szzgx.smartskip.analysis.AutoSkipMode.STANDARD)
                        .let { it.clickThreshold + if (assessment.isFirstSight) it.firstSightPenalty else 0 }
                }
            }

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
                textSignals = text.signals.map { it.name },
                isCandidate = isCandidate,
                score = assessment?.score,
                band = assessment?.band?.name,
                effectiveThreshold = threshold,
                wouldClick = assessment != null && threshold != null && assessment.score >= threshold &&
                    !assessment.isVetoed,
                vetoes = assessment?.vetoes?.map { it.name } ?: emptyList(),
                contributions = assessment?.contributions
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
            truncated = analysis.truncated,
            adContextAvailable = probe.hasAdDisclosureAnywhere,
            paymentContextAvailable = probe.hasPaymentContext,
            nodes = nodes,
        )
    }
}

/** Adapts the collector's raw probe to the analysis package's type. */
private fun AccessibilityTreeCollector.ProbeResult.toWindowRiskProbe(candidateCount: Int) =
    io.github.szzgx.smartskip.analysis.risk.WindowRiskProbe(
        hasAdCreativeOverlap = hasAdCreativeOverlap,
        hasPaymentContext = hasPaymentContext,
        hasDownloadOrStoreContext = hasDownloadOrStoreContext,
        hasNestedClickableDescendant = hasNestedClickableDescendant,
        candidateIsCentered = candidateIsCentered,
        windowHasCountdownAnywhere = false,
        distinctClickableCandidates = candidateCount,
    )
