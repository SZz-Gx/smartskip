package io.github.szzgx.smartskip.analysis.assessment

import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.FeatureContribution
import io.github.szzgx.smartskip.analysis.FeatureKind
import io.github.szzgx.smartskip.analysis.Assessment
import io.github.szzgx.smartskip.analysis.VetoReason
import io.github.szzgx.smartskip.analysis.WindowContext
import io.github.szzgx.smartskip.analysis.activity.ActivityEvidence
import io.github.szzgx.smartskip.analysis.activity.ActivityAnalyzer
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import io.github.szzgx.smartskip.analysis.position.PositionAnalyzer
import io.github.szzgx.smartskip.analysis.position.PositionEvidence
import io.github.szzgx.smartskip.analysis.position.ScreenRegion
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome
import io.github.szzgx.smartskip.analysis.risk.RiskAnalyzer
import io.github.szzgx.smartskip.analysis.risk.RiskEvidence
import io.github.szzgx.smartskip.analysis.risk.WindowRiskProbe
import io.github.szzgx.smartskip.analysis.structure.StructureAnalyzer
import io.github.szzgx.smartskip.analysis.structure.StructureEvidence
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.analysis.text.TextSignal
import io.github.szzgx.smartskip.analysis.text.TextSignalAnalyzer
import io.github.szzgx.smartskip.analysis.time.TimeAnalyzer
import io.github.szzgx.smartskip.analysis.time.TimeEvidence

/**
 * The point table.
 *
 * Design doc §6 expresses confidence as
 * `Score = W1*text + W2*position + W3*time + W4*structure + W5*activity +
 * W6*history - W7*risk`. This object is that formula written out explicitly, one
 * auditable constant per feature. Two properties are deliberately enforced by
 * construction:
 *
 *  1. **No single feature can reach the auto-click threshold on its own.** The
 *     largest single positive contribution is [KNOWN_GOOD_RULE], and even that
 *     sits below the lowest threshold in any mode unless corroborating structural
 *     evidence is also present. A keyword alone can never cause a tap.
 *  2. **Negative evidence is unbounded in effect but bounded in weight.** The
 *     scoring penalties cap out, but several risks are also expressed as hard
 *     vetoes in [AssessmentScorer], which no amount of positive evidence can
 *     outvote. That is how design doc §3.2 ("宁可漏掉，不要误点") is implemented
 *     rather than merely stated.
 */
object ScoreWeights {

    // ---------------------------------------------------------------- W1 text
    const val TEXT_SKIP_EXACT = 34
    const val TEXT_SKIP_IN_CONTENT_DESC = 26
    const val TEXT_SKIP_IN_VIEW_ID = 22
    const val TEXT_SKIP_CONTAINS_ONLY = 10
    const val TEXT_CLOSE_AFFORDANCE = 9
    const val TEXT_AD_DISCLOSURE_ON_ANCESTOR = 15
    const val TEXT_AD_DISCLOSURE_IN_WINDOW = 7
    const val TEXT_CREATIVE_TEXT = -18
    const val TEXT_INDUCEMENT = -14

    // ------------------------------------------------------------ W2 position
    const val POSITION_TOP_RIGHT = 16
    const val POSITION_TOP_LEFT = 6
    const val POSITION_BOTTOM_RIGHT = 8
    const val POSITION_CENTER_PENALTY = -22
    const val POSITION_FULLSCREEN_PENALTY = -40

    // ---------------------------------------------------------------- W3 time
    const val TIME_WITHIN_LAUNCH_WINDOW = 13
    const val TIME_COUNTDOWN_ON_NODE = 16
    const val TIME_COUNTDOWN_NEARBY = 11
    const val TIME_COUNTDOWN_SHRINKING = 8
    const val TIME_TOO_LATE_PENALTY = -16

    // ----------------------------------------------------------- W4 structure
    const val STRUCTURE_ACTIONABLE = 6
    const val STRUCTURE_SIZE_STABLE = 7
    const val STRUCTURE_INTERACTIVE_HISTORY = 9
    const val STRUCTURE_LEAF_LIKE = 6
    const val STRUCTURE_CONTAINER_PENALTY = -14
    const val STRUCTURE_HIDDEN_FROM_A11Y = -6
    const val STRUCTURE_NOT_BUTTON_LIKE = -5

    // ------------------------------------------------------------ W5 activity
    const val ACTIVITY_LAUNCHERISH = 8
    const val ACTIVITY_AD_NAMED = 18
    const val ACTIVITY_WEBVIEW = 5
    const val ACTIVITY_KNOWN_SAFE = 6

    // ------------------------------------------------------------- W6 history
    const val KNOWN_GOOD_RULE = 30
    const val USER_CONFIRMED_RULE = 22
    const val HISTORY_SUCCESS_EACH = 3
    const val HISTORY_SUCCESS_CAP = 18
    const val HISTORY_FAILURE_EACH = -12
    const val HISTORY_FAILURE_CAP = -36

    // ---------------------------------------------------------------- W7 risk
    const val RISK_AD_CREATIVE_OVERLAP = -30
    const val RISK_DOWNLOAD_OR_STORE_CONTEXT = -22
    const val RISK_PAYMENT_CONTEXT = -25
    const val RISK_NESTED_INTERACTIVE = -18
    const val RISK_LARGE_IN_CENTER = -24
    const val RISK_HIGH_CENTER_OF_MASS = -20
    const val RISK_SUSPICIOUS_CLASS = -12
    const val RISK_MULTIPLE_CANDIDATES = -16
    const val RISK_STRONG_DANGER_NEARBY = -20
    const val RISK_PRIOR_DANGER = -35

    // ----------------------------------------------------------- Aggregation
    /** Points granted when the candidate's label is also a countdown ("跳过 3"). */
    const val TEXT_SKIP_PLUS_COUNTDOWN = 2
}

/**
 * How strong the evidence has to be before SmartSkip will tap by itself.
 *
 * @property clickThreshold the score at or above which an automatic click is allowed.
 * @property firstSightPenalty added to the threshold for a package/activity that has
 *   never been decided before, implementing "unknown app → cautious mode" (§9).
 */
data class ModeThresholds(
    val clickThreshold: Int,
    val firstSightPenalty: Int,
) {
    companion object {
        /**
         * Mode A — only near-certain, previously proven controls.
         *
         * The threshold exceeds 100 on purpose for first sight: an unknown control
         * is *mathematically incapable* of reaching it, so Mode A can never tap a
         * surface SmartSkip has not already learned. That is intentional.
         */
        val SAFE = ModeThresholds(clickThreshold = 92, firstSightPenalty = 15)

        /** Mode B — the default: proven rules plus corroborated structure. */
        val STANDARD = ModeThresholds(clickThreshold = 86, firstSightPenalty = 12)

        /** Mode C — explicitly documented as capable of mis-clicks (§10). */
        val AGGRESSIVE = ModeThresholds(clickThreshold = 78, firstSightPenalty = 6)
    }
}

/**
 * Computes an [Assessment] for one candidate control.
 *
 * This object is pure: same inputs, same output, no I/O and no Android types.
 */
object AssessmentScorer {

    fun assess(
        ctx: CandidateContext,
        window: WindowContext,
        text: TextEvidence,
        position: PositionEvidence,
        time: TimeEvidence,
        structure: StructureEvidence,
        activity: ActivityEvidence,
        history: HistoryEvidence,
        risk: RiskEvidence,
    ): Assessment {
        val items = mutableListOf<FeatureContribution>()
        val vetoes = linkedSetOf<VetoReason>()

        scoreText(text, time, items)
        scorePosition(ctx, position, items)
        scoreTime(time, items)
        scoreStructure(structure, items)
        scoreActivity(activity, items)
        scoreHistory(history, items)
        scoreRisk(text, risk, items)

        collectVetoes(ctx, text, structure, risk, history, vetoes)

        val rawScore = items.sumOf { it.points }
        val score = rawScore.coerceIn(0, 100)

        return Assessment(
            score = score,
            contributions = items,
            vetoes = vetoes,
            isFirstSight = history.isNewPackageOrActivity,
        )
    }

    // ------------------------------------------------------------------ W1

    private fun scoreText(text: TextEvidence, time: TimeEvidence, out: MutableList<FeatureContribution>) {
        if (TextSignal.SKIP_EXACT in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_skip_exact",
                "控件文本恰好是“跳过”类完整词", ScoreWeights.TEXT_SKIP_EXACT,
            )
        }
        if (TextSignal.SKIP_IN_CONTENT_DESC in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_skip_content_desc",
                "contentDescription 是“跳过”类词（图标按钮）", ScoreWeights.TEXT_SKIP_IN_CONTENT_DESC,
            )
        }
        if (TextSignal.SKIP_IN_VIEW_ID in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_skip_view_id",
                "resource-id 形如广告 SDK 的 SkipView", ScoreWeights.TEXT_SKIP_IN_VIEW_ID,
            )
        }
        if (TextSignal.SKIP_CONTAINS_ONLY in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_skip_contains_only",
                "文本包含“跳过”但不是完整词（可能是诱饵）", ScoreWeights.TEXT_SKIP_CONTAINS_ONLY,
            )
        }
        if (TextSignal.CLOSE in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_close",
                "文本是关闭类符号/词", ScoreWeights.TEXT_CLOSE_AFFORDANCE,
            )
        }
        if (TextSignal.AD_DISCLOSURE_ON_ANCESTOR in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_ad_on_ancestor",
                "祖先容器带有“广告”标识", ScoreWeights.TEXT_AD_DISCLOSURE_ON_ANCESTOR,
            )
        }
        if (TextSignal.AD_DISCLOSURE_IN_WINDOW in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_ad_in_window",
                "当前窗口存在“广告”标识", ScoreWeights.TEXT_AD_DISCLOSURE_IN_WINDOW,
            )
        }
        if (TextSignal.CREATIVE_TEXT in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_creative",
                "文本是广告素材用语（下载/查看详情等）", ScoreWeights.TEXT_CREATIVE_TEXT,
            )
        }
        if (TextSignal.INDUCEMENT in text.signals) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_inducement",
                "附近存在诱导性奖励文案", ScoreWeights.TEXT_INDUCEMENT,
            )
        }
        if (time.countdownSeconds != null && text.hasSkipWording) {
            out += FeatureContribution(
                FeatureKind.TEXT, "text_skip_and_countdown",
                "“跳过”与倒计时同时出现", ScoreWeights.TEXT_SKIP_PLUS_COUNTDOWN,
            )
        }
    }

    // ------------------------------------------------------------------ W2

    private fun scorePosition(ctx: CandidateContext, position: PositionEvidence, out: MutableList<FeatureContribution>) {
        when (position.region) {
            ScreenRegion.TOP_RIGHT -> out += FeatureContribution(
                FeatureKind.POSITION, "pos_top_right",
                "位于右上角（广告跳过键最常见位置）", ScoreWeights.POSITION_TOP_RIGHT,
            )

            ScreenRegion.TOP_LEFT -> out += FeatureContribution(
                FeatureKind.POSITION, "pos_top_left",
                "位于左上角", ScoreWeights.POSITION_TOP_LEFT,
            )

            ScreenRegion.BOTTOM_RIGHT -> out += FeatureContribution(
                FeatureKind.POSITION, "pos_bottom_right",
                "位于右下角", ScoreWeights.POSITION_BOTTOM_RIGHT,
            )

            ScreenRegion.CENTER -> out += FeatureContribution(
                FeatureKind.POSITION, "pos_center",
                "位于屏幕正中（通常是广告素材而非按钮）", ScoreWeights.POSITION_CENTER_PENALTY,
            )

            else -> Unit
        }

        if (position.isHorizontallyCentered && position.region != ScreenRegion.CENTER) {
            out += FeatureContribution(
                FeatureKind.POSITION, "pos_h_centered",
                "水平居中（素材特征）", ScoreWeights.POSITION_CENTER_PENALTY / 2,
            )
        }

        if (structureIsFullscreen(ctx)) {
            out += FeatureContribution(
                FeatureKind.POSITION, "pos_fullscreen",
                "控件几乎铺满屏幕，位置特征无意义", ScoreWeights.POSITION_FULLSCREEN_PENALTY,
            )
        }
    }

    private fun structureIsFullscreen(ctx: CandidateContext): Boolean =
        PositionAnalyzer.isFullscreen(ctx.screenAreaFraction)

    // ------------------------------------------------------------------ W3

    private fun scoreTime(time: TimeEvidence, out: MutableList<FeatureContribution>) {
        if (time.appearedWithinLaunchWindow) {
            out += FeatureContribution(
                FeatureKind.TIME, "time_launch_window",
                "出现在启动后数秒内", ScoreWeights.TIME_WITHIN_LAUNCH_WINDOW,
            )
        }
        if (time.countdownSeconds != null) {
            out += FeatureContribution(
                FeatureKind.TIME, "time_countdown_on_node",
                "控件自带倒计时（剩余 ${time.countdownSeconds} 秒）", ScoreWeights.TIME_COUNTDOWN_ON_NODE,
            )
        }
        if (time.isCountdownShrinking) {
            out += FeatureContribution(
                FeatureKind.TIME, "time_countdown_shrinking",
                "倒计时确实在递减", ScoreWeights.TIME_COUNTDOWN_SHRINKING,
            )
        }
        if (time.msSinceWindowAppeared > TimeEvidence.LATE_MS &&
            time.msSinceForegroundPackage > TimeEvidence.LATE_MS
        ) {
            out += FeatureContribution(
                FeatureKind.TIME, "time_too_late",
                "已进入应用很久，此时出现“跳过”通常是正常功能", ScoreWeights.TIME_TOO_LATE_PENALTY,
            )
        }
    }

    // ------------------------------------------------------------------ W4

    private fun scoreStructure(structure: StructureEvidence, out: MutableList<FeatureContribution>) {
        if (structure.isActionable) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_actionable",
                "控件真正可点击", ScoreWeights.STRUCTURE_ACTIONABLE,
            )
        }
        if (structure.sizeIsStable) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_stable",
                "多帧中位置与尺寸稳定", ScoreWeights.STRUCTURE_SIZE_STABLE,
            )
        }
        if (structure.hasBeenClickable) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_click_history",
                "该签名此前被观察到可点击", ScoreWeights.STRUCTURE_INTERACTIVE_HISTORY,
            )
        }
        if (structure.isLeafLike) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_leaf",
                "叶节点形态，符合按钮特征", ScoreWeights.STRUCTURE_LEAF_LIKE,
            )
        }
        if (structure.isContainerLike) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_container",
                "是容器而非按钮", ScoreWeights.STRUCTURE_CONTAINER_PENALTY,
            )
        }
        if (structure.isHiddenFromAccessibility) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_hidden_a11y",
                "被标记为对无障碍不重要", ScoreWeights.STRUCTURE_HIDDEN_FROM_A11Y,
            )
        }
        if (!structure.looksLikeButtonClass && !structure.isLeafLike) {
            out += FeatureContribution(
                FeatureKind.STRUCTURE, "struct_not_button",
                "控件类型不像按钮", ScoreWeights.STRUCTURE_NOT_BUTTON_LIKE,
            )
        }
    }

    // ------------------------------------------------------------------ W5

    private fun scoreActivity(activity: ActivityEvidence, out: MutableList<FeatureContribution>) {
        if (activity.isLauncherishActivity) {
            out += FeatureContribution(
                FeatureKind.ACTIVITY, "act_launcherish",
                "当前 Activity 形如启动页", ScoreWeights.ACTIVITY_LAUNCHERISH,
            )
        }
        if (activity.isAdNamedActivity) {
            out += FeatureContribution(
                FeatureKind.ACTIVITY, "act_ad_named",
                "当前 Activity 名称含广告语义", ScoreWeights.ACTIVITY_AD_NAMED,
            )
        }
        if (activity.isWebViewSurface) {
            out += FeatureContribution(
                FeatureKind.ACTIVITY, "act_webview",
                "当前是 WebView 容器（常见于广告）", ScoreWeights.ACTIVITY_WEBVIEW,
            )
        }
        if (activity.isKnownSafeActivity) {
            out += FeatureContribution(
                FeatureKind.ACTIVITY, "act_known_safe",
                "该 Activity 此前有过成功跳过记录", ScoreWeights.ACTIVITY_KNOWN_SAFE,
            )
        }
    }

    // ------------------------------------------------------------------ W6

    private fun scoreHistory(history: HistoryEvidence, out: MutableList<FeatureContribution>) {
        if (history.knownSignature) {
            out += FeatureContribution(
                FeatureKind.HISTORY, "hist_known_rule",
                "命中本机已学习的规则（置信度 ${(history.confidence * 100).toInt()}%）",
                ScoreWeights.KNOWN_GOOD_RULE,
            )
        }
        if (history.isUserConfirmed) {
            out += FeatureContribution(
                FeatureKind.HISTORY, "hist_user_confirmed",
                "该规则已由用户确认", ScoreWeights.USER_CONFIRMED_RULE,
            )
        }
        if (history.successCount > 0) {
            val points = (history.successCount * ScoreWeights.HISTORY_SUCCESS_EACH)
                .coerceAtMost(ScoreWeights.HISTORY_SUCCESS_CAP)
            out += FeatureContribution(
                FeatureKind.HISTORY, "hist_success",
                "该规则成功跳过 ${history.successCount} 次", points,
            )
        }
        if (history.failureCount > 0) {
            val points = (history.failureCount * ScoreWeights.HISTORY_FAILURE_EACH)
                .coerceAtLeast(ScoreWeights.HISTORY_FAILURE_CAP)
            out += FeatureContribution(
                FeatureKind.HISTORY, "hist_failure",
                "该规则失误 ${history.failureCount} 次", points,
            )
        }
    }

    // ------------------------------------------------------------------ W7

    private fun scoreRisk(text: TextEvidence, risk: RiskEvidence, out: MutableList<FeatureContribution>) {
        if (risk.overlapsAdCreative) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_creative_overlap",
                "控件位于广告素材范围内", ScoreWeights.RISK_AD_CREATIVE_OVERLAP,
            )
        }
        if (risk.insideDownloadOrStoreContext) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_download_context",
                "同屏存在下载/应用商店内容", ScoreWeights.RISK_DOWNLOAD_OR_STORE_CONTEXT,
            )
        }
        if (risk.isInPaymentFlow) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_payment_context",
                "同屏存在支付/凭据相关内容", ScoreWeights.RISK_PAYMENT_CONTEXT,
            )
        }
        if (risk.isNestedInteractive) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_nested_interactive",
                "控件内部还含有可点击子控件", ScoreWeights.RISK_NESTED_INTERACTIVE,
            )
        }
        if (risk.isSuspiciouslyLargeInCenter) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_large_center",
                "位于中央且体积过大，疑似素材", ScoreWeights.RISK_LARGE_IN_CENTER,
            )
        }
        if (risk.hasHighCenterOfMass) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_high_center_of_mass",
                "控件占据屏幕主体", ScoreWeights.RISK_HIGH_CENTER_OF_MASS,
            )
        }
        if (risk.classIsSuspicious) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_suspicious_class",
                "控件类型与广告素材常见类型一致", ScoreWeights.RISK_SUSPICIOUS_CLASS,
            )
        }
        if (risk.multipleSkipShapedCandidates) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_multiple_candidates",
                "同屏出现多个“跳过”样式控件，疑似素材文字", ScoreWeights.RISK_MULTIPLE_CANDIDATES,
            )
        }
        if (TextSignal.STRONG_DANGER_NEARBY in text.signals) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_danger_nearby",
                "附近存在危险语义（下载/支付/授权）", ScoreWeights.RISK_STRONG_DANGER_NEARBY,
            )
        }
        if (risk.hasPriorDanger) {
            out += FeatureContribution(
                FeatureKind.RISK, "risk_prior_danger",
                "该控件此前造成过疑似误点", ScoreWeights.RISK_PRIOR_DANGER,
            )
        }
    }

    // --------------------------------------------------------------- Vetoes

    private fun collectVetoes(
        ctx: CandidateContext,
        text: TextEvidence,
        structure: StructureEvidence,
        risk: RiskEvidence,
        history: HistoryEvidence,
        vetoes: MutableSet<VetoReason>,
    ) {
        if (structure.isTextField) vetoes += VetoReason.EDITABLE_FIELD
        if (structure.isSelectionControl) vetoes += VetoReason.EDITABLE_FIELD
        if (!structure.isActionable) vetoes += VetoReason.NOT_ACTIONABLE
        if (!ctx.isVisibleToUser) vetoes += VetoReason.NOT_VISIBLE
        if (structure.isFullscreen) vetoes += VetoReason.IMPLAUSIBLE_SIZE

        if (risk.isInPaymentFlow) vetoes += VetoReason.PAYMENT_OR_CREDENTIAL
        if (TextSignal.STRONG_DANGER_ON_NODE in text.signals) vetoes += VetoReason.STRONG_DANGER_TEXT

        // The signature dark pattern: skip wording and an inducement or a serious
        // action on the very same control. "跳过" that reveals a reward, or that
        // installs something, is bait and must never be tapped automatically.
        val baitish = TextSignal.INDUCEMENT in text.signals || TextSignal.CREATIVE_TEXT in text.signals
        if (text.hasSkipWording && baitish) vetoes += VetoReason.SKIP_WORDING_BAIT

        if (risk.hasPriorDanger) vetoes += VetoReason.SKIP_WORDING_BAIT
        if (history.isUserRejected) vetoes += VetoReason.SKIP_WORDING_BAIT
    }

    /**
     * Runs the whole pipeline for one candidate: text, position, time, structure,
     * activity and risk analysis, then scoring and veto collection.
     *
     * This is the single entry point the accessibility layer uses, so there is
     * exactly one definition of "how a candidate is judged".
     *
     * @param dangerousPriorOutcomes mis-click outcomes previously recorded for this
     *   exact signature.
     * @param probe window-level findings gathered from the whole tree.
     * @param isKnownSafeActivity whether this activity previously produced a
     *   confirmed successful skip.
     * @param isKnownDangerousActivity whether this activity previously produced a
     *   dangerous mis-click.
     */
    fun assessFull(
        ctx: CandidateContext,
        window: WindowContext,
        probe: WindowRiskProbe,
        history: HistoryEvidence,
        dangerousPriorOutcomes: Set<DangerousOutcome>,
        isKnownSafeActivity: Boolean = false,
        isKnownDangerousActivity: Boolean = false,
    ): Assessment {
        val text = TextSignalAnalyzer.analyze(ctx)
        val position = PositionAnalyzer.analyze(
            centerX = (ctx.boundsLeft + ctx.boundsRight) / 2f,
            centerY = (ctx.boundsTop + ctx.boundsBottom) / 2f,
            width = ctx.screenWidth.toFloat(),
            height = ctx.screenHeight.toFloat(),
        )
        val time = TimeAnalyzer.analyze(
            windowAppearedAtMs = window.windowAppearedAtMs,
            packageForegroundAtMs = window.packageForegroundSinceMs,
            nowMs = window.nowMs,
            countdownSeconds = TextSignalAnalyzer.parseCountdownSeconds(ctx.ownText),
            previousCountdownSeconds = window.previousCountdownSeconds,
            distinctFrameCount = window.distinctFrameCount,
        )
        val structure = StructureAnalyzer.analyze(ctx)
        val activity = ActivityAnalyzer.analyze(
            activityName = window.activityName,
            rootClassName = window.rootClassName,
            isKnownSafeActivity = isKnownSafeActivity,
            isKnownDangerousActivity = isKnownDangerousActivity,
        )
        val risk = RiskAnalyzer.analyze(ctx, text, probe, dangerousPriorOutcomes)

        return assess(ctx, window, text, position, time, structure, activity, history, risk)
    }
}
