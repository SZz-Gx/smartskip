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
 * deliberate: vetoes are tested before the score is even considered, so a control can
 * never buy its way past a hard veto with a high score. That is the code-level
 * expression of design doc §3.2.
 *
 * The second deliberate choice is that **a surface with no rule at all is never clicked
 * automatically, in any mode**. Design doc §9 describes the progression "unknown app →
 * cautious mode → user confirms → rule → automatic next time", and a finite threshold
 * penalty cannot express that promise: a confident-looking fake skip button on a
 * brand-new app would clear any penalty. Making it an absolute gate is what makes the
 * promise true. See [DecisionEngine.FIRST_SIGHT_OBSERVATION_THRESHOLD].
 */
object DecisionEngine {

    /**
     * The threshold applied to a surface SmartSkip has never decided on and the user has
     * never confirmed.
     *
     * Above [io.github.szzgx.smartskip.analysis.Assessment.score]'s ceiling of 100, so no
     * amount of textual, positional or structural confidence can clear it. Only history — a
     * learned or user-confirmed rule — can lower the bar.
     */
    const val FIRST_SIGHT_OBSERVATION_THRESHOLD = 101

    /**
     * Relief granted for a rule the user personally vouched for. Design doc §9 explicitly
     * promises that confirmation leads to automatic action.
     */
    private const val USER_CONFIRMED_RELIEF = -8

    /**
     * Relief granted for a rule SmartSkip learned by itself. Smaller in magnitude than
     * [USER_CONFIRMED_RELIEF], because a self-inferred rule has not been vouched for.
     */
    private const val KNOWN_RULE_RELIEF = -6

    fun decide(
        assessment: Assessment,
        mode: AutoSkipMode,
        isKnownRule: Boolean,
        isUserConfirmed: Boolean,
    ): Verdict {
        val thresholds = thresholdsFor(mode)
        val adjustments = mutableListOf<ThresholdAdjustment>()

        var effective = thresholds.clickThreshold

        /*
         * The bar is lowered by trust, in two independent steps, so that the order of
         * trust is explicit and testable:
         *
         *   1. the user vouched for this exact control;
         *   2. SmartSkip learned it on its own and has seen it before.
         *
         * Both can apply at once. The first-sight penalty raises the bar only when
         * *neither* applies, which is what makes "unknown app → observe" absolute rather
         * than a finite penalty that a confident-looking fake button could outscore.
         */
        val firstSightUnconfirmed = assessment.isFirstSight && !isUserConfirmed && !isKnownRule

        if (isUserConfirmed) {
            adjustments += ThresholdAdjustment("用户已确认该规则", USER_CONFIRMED_RELIEF)
            effective += USER_CONFIRMED_RELIEF
        }

        if (isKnownRule) {
            adjustments += ThresholdAdjustment("命中本机已学习规则", KNOWN_RULE_RELIEF)
            effective += KNOWN_RULE_RELIEF
        }

        if (firstSightUnconfirmed) {
            adjustments += ThresholdAdjustment(
                "首次遇到该应用/页面且尚无任何规则，本次仅观察",
                FIRST_SIGHT_OBSERVATION_THRESHOLD - effective,
            )
            effective = FIRST_SIGHT_OBSERVATION_THRESHOLD
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
            val reason = when {
                /*
                 * Observation-only mode. The message must NOT claim the score was sufficient:
                 * the gate is `FIRST_SIGHT_OBSERVATION_THRESHOLD`, which is above 100, so nothing
                 * reaches it. An earlier wording said "已达到可自动跳过的水平" ("has reached the
                 * level where it could be auto-skipped") for a score of 51, which was simply
                 * false and would have destroyed a user's trust in every other message.
                 */
                firstSightUnconfirmed && assessment.band != ConfidenceBand.IGNORE ->
                    "评分 ${assessment.score}：该应用此前从未处理过，本次仅观察、不点击。" +
                        "在“规则”页确认该规则后，下次遇到相同结构即可自动跳过。"

                assessment.band == ConfidenceBand.IGNORE ->
                    "评分 ${assessment.score} 属于“不处理”区间（阈值 $effective）"

                assessment.band == ConfidenceBand.OBSERVE ->
                    "评分 ${assessment.score} 属于“高风险，继续观察”区间（阈值 $effective）"

                else -> "评分 ${assessment.score} 未达到自动点击阈值 $effective"
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
