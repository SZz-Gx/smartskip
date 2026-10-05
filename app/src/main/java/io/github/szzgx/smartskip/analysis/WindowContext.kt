package io.github.szzgx.smartskip.analysis

/**
 * Raw facts about the current window, in a form the decision pipeline can read
 * without touching any Android type.
 *
 * The accessibility service builds this once per frame; everything downstream —
 * analysis, scoring, thresholds, rule matching — is plain Kotlin that can be
 * driven from JVM unit tests.
 *
 * @property packageName the foreground package, or `null` when unknown.
 * @property activityName the reported class name of the window root, if known.
 * @property rootClassName the root node's view class, used for WebView detection.
 * @property packageForegroundSinceMs when the foreground package last changed.
 * @property windowAppearedAtMs when the currently reported window first appeared.
 * @property nowMs the frame's timestamp.
 * @property distinctFrameCount how many content-changed frames this window produced.
 * @property previousCountdownSeconds the countdown seen for this window earlier.
 * @property isCountdownShrinking whether the countdown was observed decreasing.
 * @property hasAdDisclosureAnywhere an "广告"/"Sponsored" marker exists in the window.
 * @property hasPaymentContext a payment or credential surface exists in the window.
 * @property hasDownloadOrStoreContext a download or app-store surface exists.
 * @property hasNestedClickableDescendant the candidate contains clickable children.
 * @property lastOutcome the outcome of SmartSkip's own previous action on this
 *   package, or `null`. Passed in rather than mutated, so this type stays a value.
 */
data class WindowContext(
    val packageName: String?,
    val activityName: String?,
    val rootClassName: String?,
    val packageForegroundSinceMs: Long,
    val windowAppearedAtMs: Long,
    val nowMs: Long,
    val distinctFrameCount: Int,
    val previousCountdownSeconds: Int?,
    val isCountdownShrinking: Boolean,
    val hasAdDisclosureAnywhere: Boolean,
    val hasPaymentContext: Boolean,
    val hasDownloadOrStoreContext: Boolean,
    val hasNestedClickableDescendant: Boolean,
    val lastOutcome: LastOutcome? = null,
)
