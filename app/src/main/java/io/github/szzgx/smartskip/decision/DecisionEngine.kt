package io.github.szzgx.smartskip.decision

import io.github.szzgx.smartskip.analysis.Assessment
import io.github.szzgx.smartskip.analysis.AutoSkipMode
import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.ConfidenceBand
import io.github.szzgx.smartskip.analysis.FeatureContribution
import io.github.szzgx.smartskip.analysis.Stage
import io.github.szzgx.smartskip.analysis.VetoReason
import io.github.szzgx.smartskip.analysis.assessment.ModeThresholds
import io.github.szzgx.smartskip.safety.ThrottleDecision

/**
 * What SmartSkip decided to do about one assessed control.
 *
 * @property shouldClick true only when every gate passed.
 * @property assessment the full, explainable scoring result.
 * @property effectiveThreshold the score that had to be reached, after mode and
 *   first-sight adjustments. Reported even when the click was refused, because
 *   "you scored 84 and needed 86" is the single most useful thing to show a user.
 * @property stage the pipeline stage that produced this verdict.
 * @property reason a short human-readable explanation.
 * @property thresholdAdjustments the individual adjustments applied to the base
 *   threshold, so the effective value is never a mystery.
 */
data class Verdict(
    val shouldClick: Boolean,
    val assessment: Assessment,
    val effectiveThreshold: Int,
    val stage: Stage,
    val reason: String,
    val thresholdAdjustments: List<ThresholdAdjustment> = emptyList(),
) {
    val band: ConfidenceBand get() = assessment.band
    val score: Int get() = assessment.score
    val isVetoed: Boolean get() = assessment.isVetoed
}

/**
 * One adjustment applied to a mode's base threshold.
 *
 * @property label why the threshold moved.
 * @property delta how far it moved; positive makes clicking harder.
 */
data class ThresholdAdjustment(val label: String, val delta: Int)

/**
 * Turns an [Assessment] into a [Verdict].
 *
 * The ordering of checks is the safety-critical design of this class, and it is
 * deliberate: vetoes are tested before the score is even considered. A control can
 * therefore never buy its way past a hard veto with a high score, no matter how
 * many positive features it accumulates. That is the code-level expression of
 * design doc §3.2.
 */
object DecisionEngine {

    /**
     * A control SmartSkip has already proven safe on this device does not need to
     * clear the first-sight bar again; the whole point of learning is that the
     * second encounter is cheaper than the first.
     */
    private const val KNOWN_RULE_FIRST_SIGHT_RELIEF = -12

    /**
     * A rule the user personally confirmed is trusted more than one SmartSkip
     * inferred, and design doc §9 explicitly promises that confirmation leads to
     * automatic action.
     */
    private const val USER_CONFIRMED_RELIEF = -6

    /**
     * How much of the first-sight penalty still applies to a known-but-unconfirmed
     * rule. Reduced rather than removed: seeing the same layout twice is evidence,
     * but not the same as the user having vouched for it.
     */
    private const val KNOWN_RULE_PENALTY_FRACTION = 0.5

    fun decide(
        assessment: Assessment,
        mode: AutoSkipMode,
        isKnownRule: Boolean,
        isUserConfirmed: Boolean,
    ): Verdict {
        val thresholds = thresholdsFor(mode)
        val adjustments = mutableListOf<ThresholdAdjustment>()

        var effective = thresholds.clickThreshold

        if (assessment.isFirstSight) {
            when {
                isUserConfirmed -> {
                    adjustments += ThresholdAdjustment("用户已确认该规则", USER_CONFIRMED_RELIEF)
                    effective += USER_CONFIRMED_RELIEF
                }

                isKnownRule -> {
                    val relief = -(thresholds.firstSightPenalty * (1.0 - KNOWN_RULE_PENALTY_FRACTION)).toInt()
                    adjustments += ThresholdAdjustment("已命中已学习规则，首次遇到惩罚减半", relief)
                    effective += relief
                }

                else -> {
                    adjustments += ThresholdAdjustment(
                        "该应用/页面此前从未处理过，采用谨慎模式",
                        thresholds.firstSightPenalty,
                    )
                    effective += thresholds.firstSightPenalty
                }
            }
        }

        if (isUserConfirmed && !assessment.isFirstSight) {
            adjustments += ThresholdAdjustment("用户已确认该规则", USER_CONFIRMED_RELIEF)
            effective += USER_CONFIRMED_RELIEF
        }

        // ------------------------------------------------------------ 1. vetoes
        if (assessment.isVetoed) {
            return Verdict(
                shouldClick = false,
                assessment = assessment,
                effectiveThreshold = effective,
                stage = Stage.VETO,
                reason = describeVetoes(assessment.vetoes),
                thresholdAdjustments = adjustments,
            )
        }

        // ------------------------------------------------------- 2. threshold
        if (assessment.score < effective) {
            val band = assessment.band
            val reason = when {
                band == ConfidenceBand.IGNORE ->
                    "评分 ${assessment.score} 属于“不处理”区间（阈值 $effective）"

                band == ConfidenceBand.OBSERVE ->
                    "评分 ${assessment.score} 属于“高风险，继续观察”区间（阈值 $effective）"

                else ->
                    "评分 ${assessment.score} 未达到自动点击阈值 $effective"
            }
            return Verdict(
                shouldClick = false,
                assessment = assessment,
                effectiveThreshold = effective,
                stage = Stage.SCORE,
                reason = reason,
                thresholdAdjustments = adjustments,
            )
        }

        // ---------------------------------------------------------- 3. proceed
        return Verdict(
            shouldClick = true,
            assessment = assessment,
            effectiveThreshold = effective,
            stage = Stage.SCORE,
            reason = "评分 ${assessment.score} 达到阈值 $effective，进入两阶段确认",
            thresholdAdjustments = adjustments,
        )
    }

    /**
     * Applies the rate limiter and circuit breaker on top of an assessment verdict.
     *
     * Separate from [decide] because the throttles depend on runtime history rather
     * than on the control's own features, and mixing the two would make the score
     * explanation harder to read.
     */
    fun applyThrottles(
        verdict: Verdict,
        circuitBreaker: ThrottleDecision,
        rateLimiter: ThrottleDecision,
    ): Verdict {
        if (!verdict.shouldClick) return verdict

        if (!circuitBreaker.allowed) {
            return verdict.copy(
                shouldClick = false,
                stage = Stage.THROTTLE,
                reason = circuitBreaker.reason,
            )
        }

        if (!rateLimiter.allowed) {
            return verdict.copy(
                shouldClick = false,
                stage = Stage.THROTTLE,
                reason = rateLimiter.reason,
            )
        }

        return verdict
    }

    fun thresholdsFor(mode: AutoSkipMode): ModeThresholds = when (mode) {
        AutoSkipMode.SAFE -> ModeThresholds.SAFE
        AutoSkipMode.STANDARD -> ModeThresholds.STANDARD
        AutoSkipMode.AGGRESSIVE -> ModeThresholds.AGGRESSIVE
    }

    /**
     * @return the most important contributions, positive first, for the "why" panel.
     */
    fun topContributions(assessment: Assessment, limit: Int = 6): List<FeatureContribution> =
        assessment.contributions
            .sortedByDescending { kotlin.math.abs(it.points) }
            .take(limit)

    private fun describeVetoes(vetoes: Set<VetoReason>): String {
        val parts = vetoes.map { reason ->
            when (reason) {
                VetoReason.EDITABLE_FIELD -> "控件是输入框或勾选项"
                VetoReason.PAYMENT_OR_CREDENTIAL -> "处于支付/凭据相关页面"
                VetoReason.STRONG_DANGER_TEXT -> "控件文本含下载/支付/授权等危险语义"
                VetoReason.SKIP_WORDING_BAIT -> "疑似“伪跳过”诱导按钮或此前已失误"
                VetoReason.NOT_ACTIONABLE -> "控件不可点击"
                VetoReason.NOT_VISIBLE -> "控件对用户不可见"
                VetoReason.IMPLAUSIBLE_SIZE -> "控件体积过大，不符合按钮特征"
            }
        }
        return "已被安全规则否决：${parts.joinToString("；")}"
    }
}

/**
 * The result of assessing every skip-shaped control in one frame.
 *
 * @property candidates all controls that produced any skip-shaped evidence.
 * @property chosen the control SmartSkip would act on, if any.
 * @property chosenVerdict the verdict for [chosen].
 * @property evaluatedCount how many controls were fully assessed.
 */
data class FrameDecision(
    val candidates: List<CandidateContext>,
    val chosen: CandidateContext?,
    val chosenVerdict: Verdict?,
    val evaluatedCount: Int,
) {
    val hasActionableChoice: Boolean get() = chosen != null && chosenVerdict?.shouldClick == true
}
