package io.github.szzgx.smartskip.analysis

/**
 * The stage of the safety pipeline a log line belongs to. Used by the debug
 * timeline so a developer can see exactly which gate refused an action.
 */
enum class Stage {
    /** An accessibility event arrived and was dispatched. */
    EVENT,

    /** A node was collected as a possible skip control. */
    CANDIDATE,

    /** Feature extraction produced an assessment. */
    SCORE,

    /** A hard veto blocked the control. */
    VETO,

    /** The two-phase confirmation re-checked the node before clicking. */
    CONFIRM,

    /** The click was dispatched. */
    CLICK,

    /** The post-click verifier reported what happened. */
    VERIFY,

    /** Rate limiting or a safety circuit-breaker suppressed an action. */
    THROTTLE,

    /** A stored rule changed. */
    RULE,

    /** An unexpected internal failure. */
    ERROR,
}

/**
 * What the post-click verifier concluded about SmartSkip's own last tap.
 *
 * Design doc §8 is emphatic that "click succeeded" is not "ad skipped", so the
 * verifier is a first-class component rather than a bit of logging.
 */
enum class LastOutcome {
    /** The splash surface disappeared and the app's real UI took over. */
    CONFIRMED_SKIPPED,

    /** Nothing observable changed; the tap may have been inert. */
    NO_OBSERVABLE_CHANGE,

    /** The app was left on a different screen but nothing dangerous happened. */
    INCONCLUSIVE,

    /** A browser, store, installer, payment page or foreign app appeared. */
    SUSPECTED_MISCLICK,

    /** The ad is still on screen; the tap did not dismiss it. */
    STILL_THERE,

    /** No verification ran because the target window changed too fast. */
    UNVERIFIED,
    ;

    /** True when this outcome should lower the trust of the responsible rule. */
    val isNegative: Boolean
        get() = this == SUSPECTED_MISCLICK || this == STILL_THERE

    /** True when this outcome should raise the trust of the responsible rule. */
    val isPositive: Boolean
        get() = this == CONFIRMED_SKIPPED
}
