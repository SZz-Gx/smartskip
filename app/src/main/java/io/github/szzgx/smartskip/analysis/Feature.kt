package io.github.szzgx.smartskip.analysis

/**
 * A single weighted contribution to the confidence score, kept so the UI can
 * explain a decision rather than presenting an opaque number.
 *
 * The design doc's scoring model (§6) is
 *
 * ```
 * Score = W1*text + W2*position + W3*time + W4*structure
 *       + W5*activity + W6*history - W7*risk
 * ```
 *
 * This project implements that model as an additive point table instead of free
 * floating weights. The reason is safety-reviewability: with a table, every point
 * of every decision can be read off and argued about, and a reviewer can check
 * that no single feature can cross the auto-click threshold on its own. The
 * mapping is one-to-one — each [FeatureKind] *is* one of the W-terms.
 */
enum class FeatureKind {
    TEXT,
    POSITION,
    TIME,
    STRUCTURE,
    ACTIVITY,
    HISTORY,
    RISK,
}

/**
 * @property kind which W-term of the model this point belongs to.
 * @property code a stable machine identifier, safe to persist and to localise.
 * @property label a short human-readable explanation shown in the UI.
 * @property points positive for evidence for, negative for evidence against.
 */
data class FeatureContribution(
    val kind: FeatureKind,
    val code: String,
    val label: String,
    val points: Int,
) {
    val isPositive: Boolean get() = points > 0
}

/** The final confidence band, as defined in design doc §6. */
enum class ConfidenceBand {
    /** 0-30 — not an ad control. Do nothing. */
    IGNORE,

    /** 30-70 — plausible but unproven. Observe only, never click. */
    OBSERVE,

    /** 70-90 — good candidate. Click only if the effective threshold allows it. */
    CANDIDATE,

    /** 90-100 — high confidence. */
    HIGH,

    ;

    companion object {
        fun of(score: Int): ConfidenceBand = when {
            score < 30 -> IGNORE
            score < 70 -> OBSERVE
            score < 90 -> CANDIDATE
            else -> HIGH
        }
    }
}

/** The three operating modes from design doc §10. */
enum class AutoSkipMode {
    /** Mode A — only rules that already proved themselves. */
    SAFE,

    /** Mode B — rules plus structural evidence. The default. */
    STANDARD,

    /** Mode C — lower thresholds, explicit mis-click warning. */
    AGGRESSIVE,
    ;

    companion object {
        val DEFAULT = STANDARD

        fun fromName(name: String?): AutoSkipMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

/** Why automatic clicking was refused regardless of score. */
enum class VetoReason {
    /** The node is an editable text field — never tap one automatically. */
    EDITABLE_FIELD,

    /** The node belongs to a payment, purchase or credential flow. */
    PAYMENT_OR_CREDENTIAL,

    /** The node's own text demands a serious action (pay, install, grant). */
    STRONG_DANGER_TEXT,

    /** Skip wording and dangerous wording on the *same* control: likely bait. */
    SKIP_WORDING_BAIT,

    /** The node is not actionable, so "clicking" it would be a blind guess. */
    NOT_ACTIONABLE,

    /** The node is not visible on screen. */
    NOT_VISIBLE,

    /** The node occupies an implausible share of the screen to be chrome. */
    IMPLAUSIBLE_SIZE,
}

/**
 * The complete, explainable assessment of one candidate control.
 *
 * @property score clamped to `0..100`.
 * @property contributions every point that produced [score], positive and negative.
 * @property vetoes hard blockers; if non-empty the control is never clicked.
 * @property isFirstSight true when this package/activity signature has never been
 *   decided before, which raises the bar for automatic clicking.
 */
data class Assessment(
    val score: Int,
    val contributions: List<FeatureContribution>,
    val vetoes: Set<VetoReason>,
    val isFirstSight: Boolean,
) {
    val band: ConfidenceBand get() = ConfidenceBand.of(score)
    val isVetoed: Boolean get() = vetoes.isNotEmpty()

    /** Points grouped by their W-term, for the explanation UI. */
    fun grouped(): Map<FeatureKind, List<FeatureContribution>> =
        contributions.groupBy { it.kind }

    /** Sum of the positive points only. */
    val positivePoints: Int get() = contributions.filter { it.isPositive }.sumOf { it.points }

    /** Sum of the negative points only (a non-positive number). */
    val negativePoints: Int get() = contributions.filterNot { it.isPositive }.sumOf { it.points }
}
