package io.github.szzgx.smartskip.analysis

/**
 * Everything the scoring engine may look at about one candidate control.
 *
 * This type deliberately contains **no Android types**. The whole decision
 * pipeline is therefore plain Kotlin and can be exercised from JVM unit tests
 * without an emulator or a device — which matters a great deal here, because a
 * mistake in this logic is a mis-click in someone's banking app.
 *
 * The accessibility layer's job is to *produce* a [CandidateContext] and, at most,
 * to carry out the resulting decision. It is not allowed to make decisions.
 *
 * @property packageName the foreground application.
 * @property activityName the reported class name of the window's root, if known.
 * @property depth distance from the window root (root = 0).
 * @property className the node's fully qualified view class.
 * @property viewIdResourceName the `resource-id`, when `flagReportViewIds` yields one.
 * @property ownText normalised visible text of the node itself.
 * @property ownContentDescription normalised `contentDescription` of the node itself.
 * @property nodeTextLen raw length of the node's visible text, used to reject
 *   nodes that are paragraphs rather than buttons.
 * @property boundsLeft/boundsTop/boundsRight/boundsBottom screen coordinates.
 * @property screenWidth/screenHeight the window bounds at assessment time.
 * @property isClickable whether the node itself accepts a click.
 * @property isLongClickable whether the node accepts a long click.
 * @property isEditable whether the node is an editable text field.
 * @property isCheckable whether the node is a checkbox/switch (never auto-tap).
 * @property isScrollable whether the node is a scroll container.
 * @property isEnabled whether the node is enabled.
 * @property isVisibleToUser whether the node is actually visible to the user.
 * @property isImportantForAccessibility the raw "important" flag, retained because
 *   ad SDKs sometimes hide skip buttons from accessibility tooling with it.
 * @property clickableAncestorDepth how many levels up the nearest clickable
 *   ancestor sits, or `null` when there is none. Ad SDKs often put the click
 *   listener on a wrapper rather than on the labelled view.
 * @property ancestorText concatenated normalised text of up to a few ancestors.
 * @property ancestorViewIds resource-ids of up to a few ancestors.
 * @property ancestorClasses classes of up to a few ancestors.
 * @property siblingText concatenated normalised text of nearby siblings/children
 *   (the node's "neighbourhood"), used for countdown and offer detection.
 * @property childCount number of direct children.
 * @property leavesUnderNode number of descendant leaf nodes, used to tell a button
 *   apart from a content container.
 * @property sameSizeSamples how many of the most recent frames showed a control of
 *   this exact size at this exact position. Chrome is geometrically stable; ad
 *   creative is not.
 * @property nodeLifetimeMs how long a control with this signature has been present
 *   in the current window, in milliseconds.
 * @property clickabilityEverObserved whether this signature has ever been observed
 *   as clickable. Guards against a click listener being attached a frame late.
 * @property isCountdownSiblingOnNode whether a countdown was seen in the node text.
 * @property isCountdownNearby whether a countdown was seen in a small nearby node.
 */
data class CandidateContext(
    val packageName: String,
    val activityName: String?,
    val depth: Int,
    val className: String,
    val viewIdResourceName: String?,
    val ownText: String,
    val ownContentDescription: String,
    val nodeTextLen: Int,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isVisibleToUser: Boolean,
    val isImportantForAccessibility: Boolean,
    val clickableAncestorDepth: Int?,
    val ancestorText: String,
    val ancestorViewIds: List<String>,
    val ancestorClasses: List<String>,
    val siblingText: String,
    val childCount: Int,
    val leavesUnderNode: Int,
    val sameSizeSamples: Int,
    val nodeLifetimeMs: Long,
    val clickabilityEverObserved: Boolean,
    val isCountdownSiblingOnNode: Boolean,
    val isCountdownNearby: Boolean,
) {
    val width: Int get() = (boundsRight - boundsLeft).coerceAtLeast(0)
    val height: Int get() = (boundsBottom - boundsTop).coerceAtLeast(0)
    val areaPx: Int get() = width * height

    /** Fraction of the screen area this node covers, in `0.0..1.0`. */
    val screenAreaFraction: Float
        get() {
            val screenArea = screenWidth.toLong() * screenHeight.toLong()
            if (screenArea <= 0L) return 0f
            return (areaPx.toFloat() / screenArea.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * A stable, non-reversible fingerprint of the control's *structure*.
     *
     * Built only from identifiers and geometry — never from displayed words — so
     * that persisting it does not persist user content (design doc §18). Two
     * visits to the same splash ad on the same device produce the same signature,
     * which is what makes rule learning possible without storing text.
     */
    val structureSignature: String
        get() {
            val id = viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
            val cls = className.substringAfterLast('.').lowercase()
            // Geometry is bucketed to 8dp-ish steps so that a one-pixel layout
            // wobble between launches does not defeat rule matching.
            val l = boundsLeft / 8
            val t = boundsTop / 8
            val r = boundsRight / 8
            val b = boundsBottom / 8
            return "$id|$cls|$l,$t,$r,$b|d$depth"
        }
}
