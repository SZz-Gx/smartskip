package io.github.szzgx.smartskip.analysis.risk

import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.analysis.text.TextSignal

/**
 * Reasons the post-click verifier reported that a tap went somewhere harmful.
 * These feed straight back into scoring, so a single bad tap permanently lowers
 * that control's trust.
 */
enum class DangerousOutcome {
    /** A browser was brought to the foreground. */
    OPENED_BROWSER,

    /** An app store listing appeared. */
    OPENED_APP_STORE,

    /** An installer or download flow appeared. */
    OPENED_DOWNLOAD,

    /** A payment page appeared. */
    OPENED_PAYMENT,

    /** A different application entirely was launched. */
    OPENED_FOREIGN_APP,

    /** A system permission dialog appeared. */
    OPENED_PERMISSION_DIALOG,

    /** A dialer / call intent appeared. */
    OPENED_DIALER,
}

/**
 * Window-level findings that no single node can reveal.
 *
 * Gathered once per frame from the whole tree, because risk is often a property
 * of the *screen*, not of the button: a skip-shaped control on a page that also
 * contains a live download button is a different proposition from the same
 * control on a clean splash screen.
 *
 * @property hasAdCreativeOverlap the candidate's bounds fall inside the ad
 *   container's bounds.
 * @property hasAdDisclosureOnlyNearby an "广告" marker exists but not on the
 *   candidate's own ancestor chain — i.e. the label is part of the creative.
 * @property hasPaymentContext a payment, purchase or credential surface exists in
 *   the window (id or class evidence).
 * @property hasDownloadOrStoreContext a download / install / app-store surface
 *   exists in the window.
 * @property hasNestedClickableDescendant the candidate contains its own clickable
 *   children, which a plain skip button does not.
 * @property candidateIsCentered the candidate sits near the horizontal centre.
 * @property windowHasCountdownAnywhere any countdown chip exists in the window.
 * @property distinctClickableCandidates how many skip-shaped controls were found in
 *   this frame. More than one is a strong sign that creative text is being
 *   mistaken for chrome.
 */
data class WindowRiskProbe(
    val hasAdCreativeOverlap: Boolean,
    val hasAdDisclosureOnlyNearby: Boolean,
    val hasPaymentContext: Boolean,
    val hasDownloadOrStoreContext: Boolean,
    val hasNestedClickableDescendant: Boolean,
    val candidateIsCentered: Boolean,
    val windowHasCountdownAnywhere: Boolean,
    val distinctClickableCandidates: Int,
) {
    companion object {
        val NONE = WindowRiskProbe(
            hasAdCreativeOverlap = false,
            hasAdDisclosureOnlyNearby = false,
            hasPaymentContext = false,
            hasDownloadOrStoreContext = false,
            hasNestedClickableDescendant = false,
            candidateIsCentered = false,
            windowHasCountdownAnywhere = false,
            distinctClickableCandidates = 0,
        )
    }
}

/**
 * Risk evidence: everything that argues *against* tapping this control.
 *
 * In the design doc's formula this is the `W7 × 风险特征` term, subtracted from
 * the score. It is implemented as negative point contributions plus a separate
 * hard-veto channel, because some risks must not be tradeable against any amount
 * of positive evidence.
 *
 * @property overlapsAdCreative the control sits on top of, or inside, advertising
 *   creative rather than on chrome.
 * @property insideDownloadOrStoreContext a download or app-store surface is
 *   present in the same window.
 * @property isInPaymentFlow a payment, purchase or credential surface is present.
 * @property hasInducementWording reward-bait wording is present.
 * @property isNestedInteractive the control contains interactive children, which a
 *   plain skip button never does.
 * @property isSuspiciouslyLargeInCenter a big centred blob — creative, not chrome.
 * @property hasHighCenterOfMass the control dominates the middle of the screen.
 * @property priorDangerousOutcomes mis-clicks already recorded for this signature.
 * @property classIsSuspicious the view class is one an ad SDK uses for creative.
 * @property multipleSkipShapedCandidates more than one skip-shaped control exists in
 *   this frame, which usually means creative text is imitating chrome.
 */
data class RiskEvidence(
    val overlapsAdCreative: Boolean,
    val insideDownloadOrStoreContext: Boolean,
    val isInPaymentFlow: Boolean,
    val hasInducementWording: Boolean,
    val isNestedInteractive: Boolean,
    val isSuspiciouslyLargeInCenter: Boolean,
    val hasHighCenterOfMass: Boolean,
    val priorDangerousOutcomes: Set<DangerousOutcome>,
    val classIsSuspicious: Boolean,
    val multipleSkipShapedCandidates: Boolean,
) {
    val hasPriorDanger: Boolean get() = priorDangerousOutcomes.isNotEmpty()

    companion object {
        val NONE = RiskEvidence(
            overlapsAdCreative = false,
            insideDownloadOrStoreContext = false,
            isInPaymentFlow = false,
            hasInducementWording = false,
            isNestedInteractive = false,
            isSuspiciouslyLargeInCenter = false,
            hasHighCenterOfMass = false,
            priorDangerousOutcomes = emptySet(),
            classIsSuspicious = false,
            multipleSkipShapedCandidates = false,
        )
    }
}

/**
 * Derives [RiskEvidence] from the candidate, the text layer and the window probe.
 *
 * Text-derived risk is read from [TextEvidence] rather than recomputed here, so
 * there is exactly one place in the codebase that decides what "dangerous wording"
 * means.
 */
object RiskAnalyzer {

    /**
     * View classes ad SDKs typically use for creative, or that are known to
     * misbehave when clicked blindly. A `VideoView` labelled "跳过" is not a button.
     */
    private val SUSPICIOUS_CLASS_HINTS = listOf(
        "videoview", "surfaceview", "textureview", "glsurfaceview",
        "webview", "canvas", "adview", "playable",
    )

    /** A control covering most of the window cannot be chrome. */
    private const val HIGH_CENTER_OF_MASS_FRACTION = 0.5f

    /** A control this large in the centre is creative. */
    private const val LARGE_FRACTION = 0.35f

    fun analyze(
        ctx: CandidateContext,
        text: TextEvidence,
        probe: WindowRiskProbe,
        dangerousOutcomes: Set<DangerousOutcome>,
    ): RiskEvidence {
        val classLower = ctx.className.lowercase()

        return RiskEvidence(
            overlapsAdCreative = probe.hasAdCreativeOverlap,
            insideDownloadOrStoreContext = probe.hasDownloadOrStoreContext,
            isInPaymentFlow = probe.hasPaymentContext || text.isPaymentId,
            hasInducementWording = TextSignal.INDUCEMENT in text.signals,
            isNestedInteractive = probe.hasNestedClickableDescendant,
            isSuspiciouslyLargeInCenter = probe.candidateIsCentered &&
                ctx.screenAreaFraction >= LARGE_FRACTION,
            hasHighCenterOfMass = ctx.screenAreaFraction >= HIGH_CENTER_OF_MASS_FRACTION,
            priorDangerousOutcomes = dangerousOutcomes,
            classIsSuspicious = SUSPICIOUS_CLASS_HINTS.any { it in classLower },
            multipleSkipShapedCandidates = probe.distinctClickableCandidates > 1,
        )
    }
}
