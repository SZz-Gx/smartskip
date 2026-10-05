package io.github.szzgx.smartskip.analysis

import io.github.szzgx.smartskip.analysis.assessment.AssessmentScorer
import io.github.szzgx.smartskip.analysis.assessment.ModeThresholds
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome
import io.github.szzgx.smartskip.analysis.risk.WindowRiskProbe
import io.github.szzgx.smartskip.decision.DecisionEngine

/**
 * Builders for the analysis tests.
 *
 * The defaults describe a *plausible but unproven* skip button: a small, clickable,
 * enabled leaf at the top-right of a 1080x2400 screen, one second after the window
 * appeared, carrying the exact text "跳过". Individual tests then perturb exactly the
 * field they are about.
 *
 * Having realistic defaults matters: if the default were harmless, every "must not
 * click" test would pass for the wrong reason.
 */
object TestFixtures {

    const val SCREEN_W = 1080
    const val SCREEN_H = 2400

    /** Top-right corner, 120x60 px, which is the canonical splash-ad skip geometry. */
    const val DEFAULT_LEFT = 900
    const val DEFAULT_TOP = 120
    const val DEFAULT_RIGHT = 1020
    const val DEFAULT_BOTTOM = 180

    fun candidate(
        ownText: String = "跳过",
        ownContentDescription: String = "",
        viewId: String? = null,
        className: String = "android.widget.TextView",
        left: Int = DEFAULT_LEFT,
        top: Int = DEFAULT_TOP,
        right: Int = DEFAULT_RIGHT,
        bottom: Int = DEFAULT_BOTTOM,
        isClickable: Boolean = true,
        isEditable: Boolean = false,
        isCheckable: Boolean = false,
        isVisibleToUser: Boolean = true,
        isEnabled: Boolean = true,
        isImportantForAccessibility: Boolean = true,
        clickableAncestorDepth: Int? = null,
        ancestorText: String = "",
        ancestorViewIds: List<String> = emptyList(),
        siblingText: String = "",
        leavesUnderNode: Int = 1,
        childCount: Int = 0,
        sameSizeSamples: Int = 3,
        nodeLifetimeMs: Long = 300L,
        clickabilityEverObserved: Boolean = true,
    ): CandidateContext = CandidateContext(
        packageName = "com.example.app",
        activityName = "com.example.app.SplashActivity",
        nodeIndex = 7,
        depth = 6,
        className = className,
        viewIdResourceName = viewId,
        ownText = ownText,
        ownContentDescription = ownContentDescription,
        nodeTextLen = ownText.length,
        boundsLeft = left,
        boundsTop = top,
        boundsRight = right,
        boundsBottom = bottom,
        screenWidth = SCREEN_W,
        screenHeight = SCREEN_H,
        isClickable = isClickable,
        isLongClickable = false,
        isEditable = isEditable,
        isCheckable = isCheckable,
        isScrollable = false,
        isEnabled = isEnabled,
        isVisibleToUser = isVisibleToUser,
        isImportantForAccessibility = isImportantForAccessibility,
        clickableAncestorDepth = clickableAncestorDepth,
        ancestorText = ancestorText,
        ancestorViewIds = ancestorViewIds,
        ancestorClasses = listOf("android.widget.FrameLayout"),
        siblingText = siblingText,
        childCount = childCount,
        leavesUnderNode = leavesUnderNode,
        sameSizeSamples = sameSizeSamples,
        nodeLifetimeMs = nodeLifetimeMs,
        clickabilityEverObserved = clickabilityEverObserved,
        isCountdownSiblingOnNode = false,
        isCountdownNearby = false,
    )

    /** A window that appeared 1s ago because the app was just launched. */
    fun window(
        nowMs: Long = 10_000L,
        windowAppearedAtMs: Long = 9_000L,
        foregroundSinceMs: Long = 8_800L,
        hasPaymentContext: Boolean = false,
        hasDownloadOrStoreContext: Boolean = false,
        hasNestedClickableDescendant: Boolean = false,
    ): WindowContext = WindowContext(
        packageName = "com.example.app",
        activityName = "com.example.app.SplashActivity",
        rootClassName = "android.widget.FrameLayout",
        packageForegroundSinceMs = foregroundSinceMs,
        windowAppearedAtMs = windowAppearedAtMs,
        nowMs = nowMs,
        distinctFrameCount = 4,
        previousCountdownSeconds = null,
        isCountdownShrinking = false,
        hasAdDisclosureAnywhere = false,
        hasPaymentContext = hasPaymentContext,
        hasDownloadOrStoreContext = hasDownloadOrStoreContext,
        hasNestedClickableDescendant = hasNestedClickableDescendant,
    )

    fun probe(
        hasAdCreativeOverlap: Boolean = false,
        hasPaymentContext: Boolean = false,
        hasDownloadOrStoreContext: Boolean = false,
        hasNestedClickableDescendant: Boolean = false,
        candidateIsCentered: Boolean = false,
        distinctClickableCandidates: Int = 1,
    ): WindowRiskProbe = WindowRiskProbe(
        hasAdCreativeOverlap = hasAdCreativeOverlap,
        hasPaymentContext = hasPaymentContext,
        hasDownloadOrStoreContext = hasDownloadOrStoreContext,
        hasNestedClickableDescendant = hasNestedClickableDescendant,
        candidateIsCentered = candidateIsCentered,
        windowHasCountdownAnywhere = false,
        distinctClickableCandidates = distinctClickableCandidates,
    )

    fun safeHistory(
        confidence: Float = 0.9f,
        successCount: Int = 3,
        failureCount: Int = 0,
        userConfirmed: Boolean = false,
        userRejected: Boolean = false,
        isNewPackageOrActivity: Boolean = false,
    ): HistoryEvidence = HistoryEvidence(
        knownSignature = true,
        confidence = confidence,
        successCount = successCount,
        failureCount = failureCount,
        isUserConfirmed = userConfirmed,
        isUserRejected = userRejected,
        isNewPackageOrActivity = isNewPackageOrActivity,
    )

    /** Scores a candidate through the whole pipeline, as the service does. */
    fun assess(
        candidate: CandidateContext,
        window: WindowContext = window(),
        probe: WindowRiskProbe = probe(),
        history: HistoryEvidence = HistoryEvidence.UNKNOWN,
        dangerous: Set<DangerousOutcome> = emptySet(),
    ): Assessment = AssessmentScorer.assessFull(
        ctx = candidate,
        window = window,
        probe = probe,
        history = history,
        dangerousPriorOutcomes = dangerous,
    )

    /** Scores and decides, returning the verdict. */
    fun decide(
        candidate: CandidateContext,
        mode: AutoSkipMode = AutoSkipMode.STANDARD,
        window: WindowContext = window(),
        probe: WindowRiskProbe = probe(),
        history: HistoryEvidence = HistoryEvidence.UNKNOWN,
        dangerous: Set<DangerousOutcome> = emptySet(),
    ) = DecisionEngine.decide(
        assessment = assess(candidate, window, probe, history, dangerous),
        mode = mode,
        isKnownRule = history.knownSignature,
        isUserConfirmed = history.isUserConfirmed,
    )

    val STANDARD_THRESHOLDS: ModeThresholds get() = ModeThresholds.STANDARD
}
