package io.github.szzgx.smartskip.safety

/**
 * Suppresses automatic taps that would arrive too fast to be meaningful.
 *
 * A splash ad animates, and some ad SDKs rebuild their skip control every frame.
 * Without a limiter SmartSkip would tap dozens of times a second at whatever
 * happens to be under the coordinates it last saw, which is precisely the
 * behaviour that turns a convenience tool into a liability.
 *
 * Both limits are checked: a global floor between any two taps, and a per-package
 * burst ceiling. The global floor exists because a fast app switch could otherwise
 * let two different apps each tap within the per-package budget.
 *
 * Not thread-safe by itself; [io.github.szzgx.smartskip.accessibility.SmartSkipAccessibilityService]
 * confines all calls to a single coroutine dispatcher.
 */
class RateLimiter(private val clock: Clock) {

    private var lastClickAtMs: Long = Long.MIN_VALUE
    private val recentClicksByPackage = mutableMapOf<String, ArrayDeque<Long>>()

    /**
     * @param packageName the app SmartSkip intends to act on.
     * @param minIntervalMs global floor between two consecutive taps.
     * @param maxPerMinute per-package ceiling inside a rolling one-minute window.
     * @return `null` when the tap may proceed, or the reason it must not.
     */
    fun check(packageName: String, minIntervalMs: Long, maxPerMinute: Int): ThrottleDecision {
        val now = clock.nowMs()

        if (lastClickAtMs != Long.MIN_VALUE) {
            val sinceLast = now - lastClickAtMs
            if (sinceLast < minIntervalMs) {
                return ThrottleDecision(
                    allowed = false,
                    stage = io.github.szzgx.smartskip.analysis.Stage.THROTTLE,
                    reason = "距上次点击仅 ${sinceLast}ms，低于最小间隔 ${minIntervalMs}ms",
                )
            }
        }

        val history = recentClicksByPackage.getOrPut(packageName) { ArrayDeque() }
        prune(history, now)
        if (history.size >= maxPerMinute) {
            return ThrottleDecision(
                allowed = false,
                stage = io.github.szzgx.smartskip.analysis.Stage.THROTTLE,
                reason = "$packageName 在最近一分钟内已自动点击 ${history.size} 次，达到上限 $maxPerMinute",
            )
        }

        return ThrottleDecision(
            allowed = true,
            stage = io.github.szzgx.smartskip.analysis.Stage.THROTTLE,
            reason = "节流检查通过",
        )
    }

    /** Records that a tap was actually dispatched. */
    fun record(packageName: String) {
        val now = clock.nowMs()
        lastClickAtMs = now
        val history = recentClicksByPackage.getOrPut(packageName) { ArrayDeque() }
        prune(history, now)
        history.addLast(now)
    }

    /** Forgets all history, used when the user re-enables automatic skipping. */
    fun reset() {
        lastClickAtMs = Long.MIN_VALUE
        recentClicksByPackage.clear()
    }

    private fun prune(history: ArrayDeque<Long>, now: Long) {
        while (history.isNotEmpty() && now - history.first() > WINDOW_MS) {
            history.removeFirst()
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}

/**
 * The user-visible safety state of one application, as summarised for the UI.
 *
 * @property packageName the app.
 * @property dangerousMisclickCount lifetime dangerous mis-clicks here.
 * @property consecutiveFailureCount consecutive negative outcomes, reset by a success.
 * @property isSuspended true when automatic action is paused for this app.
 * @property suspensionReason why, in words the user can act on.
 * @property needsUserAcknowledgement true when SmartSkip wants the user to look at
 *   this app before it resumes acting on it.
 */
data class PackageSafety(
    val packageName: String,
    val dangerousMisclickCount: Int = 0,
    val consecutiveFailureCount: Int = 0,
    val isSuspended: Boolean = false,
    val suspensionReason: String? = null,
    val needsUserAcknowledgement: Boolean = false,
)

/**
 * The per-package circuit breaker (design doc §16's "two-stage confirmation" plus
 * §8's "stop automatic operation immediately").
 *
 * This is where the project's central promise is enforced: after SmartSkip causes a
 * harmful tap, it stops acting on that application rather than continuing to
 * experiment on the user's device.
 */
object CircuitBreaker {

    /**
     * Computes the next safety state after an outcome was recorded.
     *
     * Pure: given the previous state and a verification result, it returns the new
     * state. Persisting it is the caller's job.
     *
     * @param previous the state before this outcome.
     * @param result what the verifier concluded.
     * @param stopAfterDangerousMisclicks how many dangerous mis-clicks in this
     *   package before automatic action is suspended.
     */
    fun afterOutcome(
        previous: PackageSafety,
        result: VerificationResult,
        stopAfterDangerousMisclicks: Int,
    ): PackageSafety {
        val dangerous = if (result.isDangerous) previous.dangerousMisclickCount + 1 else previous.dangerousMisclickCount
        val consecutiveFailure = when {
            result.outcome.isPositive -> 0
            result.outcome.isNegative -> previous.consecutiveFailureCount + 1
            else -> previous.consecutiveFailureCount
        }

        val shouldSuspend = result.isDangerous && dangerous >= stopAfterDangerousMisclicks

        return previous.copy(
            dangerousMisclickCount = dangerous,
            consecutiveFailureCount = consecutiveFailure,
            isSuspended = previous.isSuspended || shouldSuspend,
            suspensionReason = when {
                shouldSuspend -> "该应用已发生 $dangerous 次疑似危险误点，已自动暂停自动跳过"
                else -> previous.suspensionReason
            },
            needsUserAcknowledgement = previous.needsUserAcknowledgement || shouldSuspend,
        )
    }

    /**
     * Whether an automatic tap may proceed for this package.
     *
     * A suspension is absolute for automatic action: the user can still skip by hand,
     * and SmartSkip keeps observing, but it will not tap until the suspension is
     * cleared deliberately.
     */
    fun mayActAutomatically(state: PackageSafety): ThrottleDecision = when {
        state.isSuspended -> ThrottleDecision(
            allowed = false,
            stage = io.github.szzgx.smartskip.analysis.Stage.THROTTLE,
            reason = state.suspensionReason ?: "该应用已被暂停自动操作",
        )

        else -> ThrottleDecision(
            allowed = true,
            stage = io.github.szzgx.smartskip.analysis.Stage.THROTTLE,
            reason = "断路器允许自动操作",
        )
    }

    /**
     * @return the state with the suspension lifted, for when the user explicitly
     *   asks SmartSkip to try again.
     */
    fun clearSuspension(
        previous: PackageSafety,
        keepDangerousHistory: Boolean = true,
    ): PackageSafety = previous.copy(
        isSuspended = false,
        suspensionReason = null,
        needsUserAcknowledgement = false,
        consecutiveFailureCount = 0,
        dangerousMisclickCount = if (keepDangerousHistory) previous.dangerousMisclickCount else 0,
    )
}
