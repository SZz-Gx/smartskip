package io.github.szzgx.smartskip.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One observation of a candidate skip control, and what SmartSkip did about it.
 *
 * ## Deviation from the design document, and why
 *
 * Design doc §12 lists a `nodeText` column. Design doc §18 forbids persisting
 * user content and asks for "text features as a hash or category". Those two
 * requirements cannot both be met, so §18 wins: this table stores
 * [textFeatures] — the *classification* of the label — and never the label
 * itself.
 *
 * The reasoning is that a skip button's value as a memory is "this was a skip
 * button", not "it said 跳过". Storing the raw string would mean writing whatever
 * happened to be on screen into a database, and a short label is not made safe by
 * hashing it either, since short strings are trivially brute-forced. Text that
 * needs to be *shown* is shown live, from the accessibility tree, and forgotten.
 *
 * @property packageName the foreground app.
 * @property activityName the window's reported class name, if any.
 * @property timestampMs when the control was assessed.
 * @property textFeatures classification flags, e.g. `skip_exact,ad_ancestor`.
 * @property resourceId the node's `resource-id`. Not user content: it is chosen by
 *   the app's developer and is the same for everyone running that build.
 * @property className the node's fully qualified view class.
 * @property boundsLeft/boundsTop/boundsRight/boundsBottom the node's screen bounds.
 * @property screenWidth/screenHeight the window size, so bounds remain interpretable
 *   across rotations and differing devices.
 * @property structureSignature the bucketed structural fingerprint used for rule
 *   matching and for detecting repeat visits.
 * @property score the confidence score at decision time.
 * @property band the confidence band the score fell into.
 * @property action what SmartSkip decided to do.
 * @property result what the post-click verifier subsequently observed.
 * @property vetoReasons why an action was refused, if it was.
 * @property topFeatures the few highest-impact contributions, for the UI's
 *   "why did it decide that" panel. Stored as `code:points` pairs.
 * @property durationMs how long the assessed window remained on screen.
 * @property mode the operating mode in force at decision time, so that a mode
 *   change does not retroactively rewrite history.
 */
@Entity(
    tableName = "advertisement_event",
    indices = [
        Index(value = ["timestamp_ms"]),
        Index(value = ["package_name", "timestamp_ms"]),
        Index(value = ["structure_signature"]),
    ],
)
data class AdvertisementEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "activity_name")
    val activityName: String?,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,

    @ColumnInfo(name = "text_features")
    val textFeatures: String,

    @ColumnInfo(name = "resource_id")
    val resourceId: String?,

    @ColumnInfo(name = "class_name")
    val className: String,

    @ColumnInfo(name = "bounds_left")
    val boundsLeft: Int,

    @ColumnInfo(name = "bounds_top")
    val boundsTop: Int,

    @ColumnInfo(name = "bounds_right")
    val boundsRight: Int,

    @ColumnInfo(name = "bounds_bottom")
    val boundsBottom: Int,

    @ColumnInfo(name = "screen_width")
    val screenWidth: Int,

    @ColumnInfo(name = "screen_height")
    val screenHeight: Int,

    @ColumnInfo(name = "structure_signature")
    val structureSignature: String,

    @ColumnInfo(name = "score")
    val score: Int,

    @ColumnInfo(name = "band")
    val band: String,

    @ColumnInfo(name = "action")
    val action: String,

    @ColumnInfo(name = "result")
    val result: String,

    @ColumnInfo(name = "veto_reasons")
    val vetoReasons: String,

    @ColumnInfo(name = "top_features")
    val topFeatures: String,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "mode")
    val mode: String,
)

/**
 * A learned, per-application rule: "in this app, this control is a skip button".
 *
 * Rules are keyed by [packageName] + [activityName] + [structureSignature] rather
 * than by text, so the rule survives an ad SDK changing its wording or rotating
 * between several creatives while keeping the same layout.
 *
 * @property confidence `0.0`..`1.0`. Raised by confirmed skips, cut hard by
 *   suspected mis-clicks.
 * @property successCount confirmed successful skips.
 * @property failureCount suspected mis-clicks attributed to this rule.
 * @property dangerousFailureCount the subset of failures that reached a browser,
 *   store, installer or payment page. Any non-zero value is grounds for refusing
 *   automatic action on this signature.
 * @property userConfirmed the user explicitly vouched for this rule; it may then be
 *   applied automatically even on a first sighting.
 * @property userRejected the user explicitly marked this rule wrong. It is never
 *   applied automatically again.
 * @property isEnabled whether the rule is active.
 * @property textObservation the privacy-safe text classification recorded when the
 *   rule was created.
 * @property firstSeenMs/lastUsedMs bookkeeping timestamps.
 */
@Entity(
    tableName = "app_rule",
    primaryKeys = ["package_name", "activity_name", "structure_signature"],
    indices = [Index(value = ["package_name"])],
)
data class AppRuleEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /**
     * Empty string rather than null, because a null column cannot participate in a
     * composite primary key.
     */
    @ColumnInfo(name = "activity_name")
    val activityName: String,

    @ColumnInfo(name = "structure_signature")
    val structureSignature: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "success_count")
    val successCount: Int,

    @ColumnInfo(name = "failure_count")
    val failureCount: Int,

    @ColumnInfo(name = "dangerous_failure_count")
    val dangerousFailureCount: Int,

    @ColumnInfo(name = "user_confirmed")
    val userConfirmed: Boolean,

    @ColumnInfo(name = "user_rejected")
    val userRejected: Boolean,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,

    @ColumnInfo(name = "text_observation")
    val textObservation: String,

    @ColumnInfo(name = "first_seen_ms")
    val firstSeenMs: Long,

    @ColumnInfo(name = "last_used_ms")
    val lastUsedMs: Long,
)

/**
 * A per-package safety circuit breaker (design doc §16).
 *
 * Once a package has produced a dangerous mis-click, SmartSkip stops acting on it
 * automatically until the user intervenes. This is the machine-readable form of
 * "宁可漏掉一个广告，也不要替用户误点一次危险按钮".
 *
 * @property packageName the app being tracked.
 * @property humanLabel a human-readable app label, cached so the UI can show a
 *   name even before the package list is queried.
 * @property dangerousMisclickCount lifetime count of dangerous mis-clicks here.
 * @property consecutiveFailureCount consecutive negative outcomes, reset by a
 *   success.
 * @property isAutoSkipSuspended true when automatic action is paused for this app.
 * @property suspensionReason human-readable explanation for the suspension.
 * @property suspendedAtMs when the suspension started.
 * @property observationOnly true when the user asked to watch this app without ever
 *   acting on it.
 * @property firstSeenMs/lastSeenMs bookkeeping timestamps.
 */
@Entity(tableName = "package_state", primaryKeys = ["package_name"])
data class PackageStateEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "human_label")
    val humanLabel: String?,

    @ColumnInfo(name = "dangerous_misclick_count")
    val dangerousMisclickCount: Int,

    @ColumnInfo(name = "consecutive_failure_count")
    val consecutiveFailureCount: Int,

    @ColumnInfo(name = "is_auto_skip_suspended")
    val isAutoSkipSuspended: Boolean,

    @ColumnInfo(name = "suspension_reason")
    val suspensionReason: String?,

    @ColumnInfo(name = "suspended_at_ms")
    val suspendedAtMs: Long?,

    @ColumnInfo(name = "observation_only")
    val observationOnly: Boolean,

    @ColumnInfo(name = "first_seen_ms")
    val firstSeenMs: Long,

    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long,
)
