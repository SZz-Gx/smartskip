package io.github.szzgx.smartskip.analysis.time

/**
 * Timing evidence.
 *
 * A splash ad is defined as much by *when* it appears as by what it contains: it
 * shows up immediately after the app is launched, before the user's real task
 * begins, and it lasts a handful of seconds. The same "跳过" text appearing 40
 * seconds into a session is very likely part of a tutorial, a video player or a
 * wizard — none of which should be tapped automatically.
 *
 * @property msSinceWindowAppeared how long the current window has been on screen.
 * @property msSinceForegroundPackage how long the current package has been in the
 *   foreground, which is the better proxy for "app just launched" because some
 *   apps show the splash ad before swapping to their main activity.
 * @property countdownSeconds remaining seconds advertised by a countdown chip.
 * @property appearedWithinLaunchWindow true when both time budgets are satisfied.
 * @property isCountdownShrinking true when the same countdown node was observed
 *   with a larger value moments ago, which proves it is really counting down.
 * @property distinctFrameCount how many frames this window has produced so far.
 */
data class TimeEvidence(
    val msSinceWindowAppeared: Long,
    val msSinceForegroundPackage: Long,
    val countdownSeconds: Int?,
    val appearedWithinLaunchWindow: Boolean,
    val isCountdownShrinking: Boolean,
    val distinctFrameCount: Int,
) {
    companion object {
        /** A control seen this soon after the window appeared is splash-like. */
        const val EARLY_WINDOW_MS = 2_500L

        /** ...and this soon after the app came to the foreground. */
        const val EARLY_LAUNCH_MS = 6_000L

        /** After this long, "skip" wording almost certainly belongs to real UI. */
        const val LATE_MS = 15_000L
    }
}

/**
 * Builds [TimeEvidence] from raw timestamps tracked by the accessibility service.
 */
object TimeAnalyzer {

    /**
     * @param windowAppearedAtMs when the currently reported window first appeared.
     * @param packageForegroundAtMs when the foreground package last changed.
     * @param nowMs the current time.
     * @param countdownSeconds parsed countdown, if any.
     * @param previousCountdownSeconds the countdown observed for this window on an
     *   earlier frame.
     * @param distinctFrameCount how many content-changed frames this window produced.
     */
    fun analyze(
        windowAppearedAtMs: Long,
        packageForegroundAtMs: Long,
        nowMs: Long,
        countdownSeconds: Int?,
        previousCountdownSeconds: Int?,
        distinctFrameCount: Int,
    ): TimeEvidence {
        val sinceWindow = if (windowAppearedAtMs <= 0L) Long.MAX_VALUE else (nowMs - windowAppearedAtMs).coerceAtLeast(0L)
        val sincePackage =
            if (packageForegroundAtMs <= 0L) Long.MAX_VALUE else (nowMs - packageForegroundAtMs).coerceAtLeast(0L)

        val withinLaunch =
            sinceWindow <= TimeEvidence.EARLY_WINDOW_MS || sincePackage <= TimeEvidence.EARLY_LAUNCH_MS

        val shrinking = previousCountdownSeconds != null &&
            countdownSeconds != null &&
            countdownSeconds < previousCountdownSeconds

        return TimeEvidence(
            msSinceWindowAppeared = sinceWindow,
            msSinceForegroundPackage = sincePackage,
            countdownSeconds = countdownSeconds,
            appearedWithinLaunchWindow = withinLaunch,
            isCountdownShrinking = shrinking,
            distinctFrameCount = distinctFrameCount,
        )
    }
}
