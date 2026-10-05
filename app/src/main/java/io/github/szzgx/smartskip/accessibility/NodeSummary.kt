package io.github.szzgx.smartskip.accessibility

/**
 * An immutable copy of the facts SmartSkip needs from one node.
 *
 * Every field is a primitive or a String, deliberately: nothing here references
 * `AccessibilityNodeInfo`. Holding live node references across a coroutine
 * suspension leaks the window they belong to and produces `IllegalStateException`
 * once the window is gone, so the service copies what it needs and drops the tree
 * immediately.
 *
 * This type is still Android-free, so the analysis layer can be unit tested by
 * constructing summaries directly.
 *
 * @property nodeIndex index into [WindowAnalysis.nodes], used to express
 *   parent/child relationships without object graphs.
 * @property parentIndex index of the parent, or `-1` for the root.
 * @property childIndices indices of direct children.
 * @property depth distance from the window root, root = 0.
 * @property text the node's visible text, already trimmed but not normalised.
 * @property contentDescription the node's `contentDescription`.
 * @property viewIdResourceName the `resource-id`, when reported.
 * @property className the fully qualified view class name.
 * @property packageName the package that owns the node.
 * @property boundsLeft/boundsTop/boundsRight/boundsBottom screen coordinates.
 * @property isClickable/isLongClickable/isEditable/isCheckable/isScrollable/
 *   isEnabled/isFocusable/isSelected the node's action and state flags.
 * @property isVisibleToUser whether the node is actually visible.
 * @property isImportantForAccessibility the raw importance flag.
 * @property isPassword whether the node is a password field. Recorded so the
 *   service can refuse even to *look* at it, let alone tap it.
 * @property isWebView whether the node or an ancestor is a WebView.
 * @property clickableAncestorDepth levels up to the nearest clickable ancestor, or
 *   `null` when there is none. Ad SDKs frequently attach the click listener to a
 *   wrapper and leave the labelled view inert, so "the node is not clickable" is not
 *   on its own a reason to ignore it.
 * @property ancestorIds resource-ids of up to a few ancestors, nearest last.
 * @property ancestorClasses view classes of up to a few ancestors, nearest last.
 * @property ancestorTexts visible texts of up to a few ancestors, nearest last.
 * @property leavesUnderNode number of leaf nodes in this subtree, including itself
 *   when it has no children.
 * @property descendantCount total number of descendants.
 * @property descendantClickableCount clickable descendants, used to detect a
 *   container that is not really a button.
 */
data class NodeSummary(
    val nodeIndex: Int,
    val parentIndex: Int,
    val childIndices: List<Int>,
    val depth: Int,
    val text: String,
    val contentDescription: String,
    val viewIdResourceName: String?,
    val className: String,
    val packageName: String?,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isFocusable: Boolean,
    val isSelected: Boolean,
    val isVisibleToUser: Boolean,
    val isImportantForAccessibility: Boolean,
    val isPassword: Boolean,
    val isWebView: Boolean,
    val clickableAncestorDepth: Int?,
    val ancestorIds: List<String>,
    val ancestorClasses: List<String>,
    val ancestorTexts: List<String>,
    val leavesUnderNode: Int,
    val descendantCount: Int,
    val descendantClickableCount: Int,
) {
    val width: Int get() = (boundsRight - boundsLeft).coerceAtLeast(0)
    val height: Int get() = (boundsBottom - boundsTop).coerceAtLeast(0)
    val areaPx: Int get() = width * height

    /** All the identifying strings of this node, for payment-shaped view detection. */
    fun identityStrings(): List<String> = buildList {
        add(className)
        viewIdResourceName?.let { add(it) }
    }
}

/**
 * One captured frame of the foreground window.
 *
 * @property packageName the package owning the window.
 * @property activityName the reported class name of the window, if available.
 * @property rootClassName the root node's view class.
 * @property nodes every captured node, in traversal order.
 * @property screenWidth/screenHeight the window's size, derived from the root bounds.
 * @property capturedAtMs when the frame was captured.
 * @property truncated true when traversal hit its node budget, meaning the tree is
 *   incomplete. Analysis still runs, but the risk probe reports reduced confidence.
 */
data class WindowAnalysis(
    val packageName: String?,
    val activityName: String?,
    val rootClassName: String?,
    val nodes: List<NodeSummary>,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAtMs: Long,
    val truncated: Boolean,
) {
    /**
     * A cheap content fingerprint used to answer "did this window change".
     *
     * Only a hash of structural identifiers is kept, never text, so comparing two
     * frames never requires storing what was on screen (design doc §18).
     */
    val structuralFingerprint: Int
        get() = nodes.take(FINGERPRINT_NODES).joinToString("|") { node ->
            val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val cls = node.className.substringAfterLast('.')
            "${node.depth}:$id:$cls:${node.boundsLeft},${node.boundsTop}"
        }.hashCode()

    companion object {
        /**
         * Fingerprinting the whole tree would be both slow and unstable, because
         * ad creative animates. The first N nodes are the stable skeleton.
         */
        const val FINGERPRINT_NODES = 40

        val EMPTY = WindowAnalysis(
            packageName = null,
            activityName = null,
            rootClassName = null,
            nodes = emptyList(),
            screenWidth = 0,
            screenHeight = 0,
            capturedAtMs = 0L,
            truncated = false,
        )
    }
}
