package io.github.szzgx.smartskip.safety

import io.github.szzgx.smartskip.analysis.LastOutcome
import io.github.szzgx.smartskip.analysis.Stage
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome

/**
 * A monotonic millisecond clock.
 *
 * Injected rather than read from `System.currentTimeMillis()` so that the rate
 * limiter and the verifier's timing can be tested deterministically. Safety
 * behaviour that has only been reasoned about is not safety behaviour.
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * A snapshot of what the device looked like immediately before SmartSkip tapped.
 *
 * @property packageName the foreground package at tap time.
 * @property activityName the foreground activity at tap time.
 * @property windowSig a fingerprint of the window's contents, used to tell "the ad
 *   went away" from "nothing happened".
 * @property nodeSignature the structure signature of the control that was tapped.
 */
data class PreClickSnapshot(
    val packageName: String?,
    val activityName: String?,
    val windowSig: Int,
    val nodeSignature: String,
)

/**
 * What the device looked like after the tap, plus what SmartSkip can infer.
 *
 * @property packageName the foreground package after the settle delay.
 * @property activityName the foreground activity after the settle delay.
 * @property windowSig a fingerprint of the new window's contents.
 * @property viewIdOrClassNames node ids and class names in the new window, used to
 *   recognise a payment or credential surface even inside the same app.
 * @property elapsedMs how long the verifier waited before sampling.
 * @property packageChanged whether the foreground package is different from before.
 */
data class PostClickSnapshot(
    val packageName: String?,
    val activityName: String?,
    val windowSig: Int,
    val viewIdOrClassNames: List<String>,
    val elapsedMs: Long,
    val packageChanged: Boolean,
)

/**
 * The verifier's conclusion, with the reasoning attached.
 *
 * @property outcome the classification.
 * @property dangerousOutcome the specific hazard, when [outcome] is
 *   [LastOutcome.SUSPECTED_MISCLICK].
 * @property explanation a human-readable account, surfaced in the log and the UI.
 */
data class VerificationResult(
    val outcome: LastOutcome,
    val dangerousOutcome: DangerousOutcome?,
    val explanation: String,
) {
    val isDangerous: Boolean get() = dangerousOutcome != null
}

/**
 * Decides whether a tap did what SmartSkip intended.
 *
 * Design doc §8: "点击成功 ≠ 广告跳过成功". This object is that sentence implemented.
 *
 * It is pure and static. The accessibility service supplies the snapshots and is
 * responsible for the actual waiting; all judgement lives here so it can be tested
 * and reviewed in isolation.
 */
object ClickVerifier {

    /**
     * How long to wait before deciding. Splash ads animate, and an ad SDK may take
     * a few hundred milliseconds to tear its own window down, so sampling too early
     * would report a false "nothing changed".
     */
    const val SETTLE_DELAY_MS = 600L

    /**
     * An intentionally long wait used only when the first sample is inconclusive.
     */
    const val SECOND_SAMPLE_DELAY_MS = 400L

    /**
     * A window fingerprint, as a content hash. Only the *hash* is retained, which
     * is enough to answer "did the window change" without storing what was on
     * screen (design doc §18).
     */
    fun fingerprintOf(parts: Collection<String>): Int = parts.hashCode()

    /**
     * Classifies the transition.
     *
     * The order of these checks is the safety-critical part, and it is deliberate:
     * danger is tested *first*, before any notion of success. If a tap both made the
     * ad disappear and opened a payment page, that is a mis-click, not a win.
     */
    fun verify(before: PreClickSnapshot, after: PostClickSnapshot): VerificationResult {
        // ------------------------------------------------------------ 1. danger
        val dangerous = OutcomeClassifier.classify(after.packageName, after.activityName)
        if (dangerous != null) {
            return VerificationResult(
                outcome = LastOutcome.SUSPECTED_MISCLICK,
                dangerousOutcome = dangerous,
                explanation = describeDanger(dangerous, after),
            )
        }

        if (OutcomeClassifier.looksLikePaymentSurface(after.viewIdOrClassNames)) {
            return VerificationResult(
                outcome = LastOutcome.SUSPECTED_MISCLICK,
                dangerousOutcome = DangerousOutcome.OPENED_PAYMENT,
                explanation = "点击后窗口出现支付/凭据相关控件，判定为疑似误点",
            )
        }

        // ------------------------------------------------- 2. left the app entirely
        if (after.packageChanged) {
            // A jump to a different app that is not one of the known hazards is
            // still not what a skip button is supposed to do.
            return VerificationResult(
                outcome = LastOutcome.SUSPECTED_MISCLICK,
                dangerousOutcome = DangerousOutcome.OPENED_FOREIGN_APP,
                explanation = "点击后前台应用变为 ${after.packageName ?: "未知"}，疑似被带离原应用",
            )
        }

        // ------------------------------------------------------------- 3. success
        val activityChanged = activityDiffers(before.activityName, after.activityName)
        val windowChanged = before.windowSig != after.windowSig

        if (activityChanged && windowChanged) {
            return VerificationResult(
                outcome = LastOutcome.CONFIRMED_SKIPPED,
                dangerousOutcome = null,
                explanation = "点击后页面已切换（${before.activityName ?: "?"} → ${after.activityName ?: "?"}），判定跳过成功",
            )
        }

        if (activityChanged) {
            // The activity changed but the content fingerprint did not, which is
            // possible for a transition between two structurally identical screens.
            return VerificationResult(
                outcome = LastOutcome.CONFIRMED_SKIPPED,
                dangerousOutcome = null,
                explanation = "点击后 Activity 已切换，判定跳过成功",
            )
        }

        if (windowChanged) {
            // The content changed but stayed in the same activity. A splash ad that
            // lives in the launcher activity looks exactly like this.
            return VerificationResult(
                outcome = LastOutcome.CONFIRMED_SKIPPED,
                dangerousOutcome = null,
                explanation = "点击后页面内容发生变化，判定跳过成功",
            )
        }

        // ---------------------------------------------------------- 4. no change
        if (after.elapsedMs >= SETTLE_DELAY_MS) {
            return VerificationResult(
                outcome = LastOutcome.NO_OBSERVABLE_CHANGE,
                dangerousOutcome = null,
                explanation = "点击后页面没有可观察的变化，可能是无效点击或广告仍在",
            )
        }

        return VerificationResult(
            outcome = LastOutcome.INCONCLUSIVE,
            dangerousOutcome = null,
            explanation = "等待时间过短，无法判断",
        )
    }

    /**
     * Records that a tap could not be verified because the target window vanished
     * before sampling — an ambiguous case that must not be counted as either a
     * success or a failure.
     */
    fun unverified(reason: String): VerificationResult = VerificationResult(
        outcome = LastOutcome.UNVERIFIED,
        dangerousOutcome = null,
        explanation = reason,
    )

    private fun activityDiffers(before: String?, after: String?): Boolean {
        if (before == null && after == null) return false
        return before != after
    }

    private fun describeDanger(outcome: DangerousOutcome, after: PostClickSnapshot): String {
        val target = after.packageName ?: "未知应用"
        return when (outcome) {
            DangerousOutcome.OPENED_BROWSER -> "点击后打开了浏览器（$target），判定为疑似误点"
            DangerousOutcome.OPENED_APP_STORE -> "点击后打开了应用商店（$target），判定为疑似误点"
            DangerousOutcome.OPENED_DOWNLOAD -> "点击后进入安装/下载流程（$target），判定为疑似误点"
            DangerousOutcome.OPENED_PAYMENT -> "点击后进入支付页面（$target），判定为疑似误点"
            DangerousOutcome.OPENED_FOREIGN_APP -> "点击后跳转到其他应用（$target），判定为疑似误点"
            DangerousOutcome.OPENED_PERMISSION_DIALOG -> "点击后弹出系统权限申请，判定为疑似误点"
            DangerousOutcome.OPENED_DIALER -> "点击后进入拨号界面，判定为疑似误点"
        }
    }
}

/**
 * An explanation string for why an action was throttled, for the debug timeline.
 */
data class ThrottleDecision(val allowed: Boolean, val stage: Stage, val reason: String)
