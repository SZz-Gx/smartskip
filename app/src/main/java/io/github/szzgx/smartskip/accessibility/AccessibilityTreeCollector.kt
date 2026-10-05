package io.github.szzgx.smartskip.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Copies the live accessibility tree into an immutable [WindowAnalysis].
 *
 * This is the only place in the project that touches `AccessibilityNodeInfo` during
 * analysis, and it is written defensively on purpose: every call into the platform
 * can throw once a window has gone away, and a splash ad's window goes away
 * constantly. A capture that fails halfway returns whatever it collected, because a
 * partial frame is still useful and a crash inside an accessibility service is very
 * visible to the user.
 *
 * No `AccessibilityNodeInfo` reference escapes this class. That is what allows the
 * analysis layer to be unit tested, and it is also what prevents the classic
 * accessibility-service memory leak.
 */
object AccessibilityTreeCollector {

    /**
     * Hard budget on nodes examined per frame. Some apps have enormous trees — a
     * WebView-based page can run to thousands of nodes — and this runs on every
     * content-changed event, which can fire dozens of times per second.
     */
    private const val MAX_NODES = 2_000

    /** Depth beyond which a tree is assumed to be a content blob. */
    private const val MAX_DEPTH = 40

    /** Longest text captured from any single node. */
    private const val MAX_NODE_TEXT = 240

    private val AD_CONTAINER_ID_HINTS = listOf(
        "splash", "adcontainer", "ad_container", "adview", "ad_view",
        "nativead", "native_ad", "interstitial", "advert",
        "tt_splash", "gdt_", "ksad", "bd_ad", "mimo_",
    )

    private val PAYMENT_ID_HINTS = listOf(
        "pay", "payment", "purchase", "buy", "order", "checkout", "wallet",
        "password", "captcha", "verify", "otp", "sms_code", "smscode",
    )

    private val DOWNLOAD_ID_HINTS = listOf(
        "download", "install", "appstore", "app_store", "market",
    )

    /**
     * Captures [root] and its descendants.
     *
     * @param root the window root. The caller owns it and stays responsible for
     *   recycling it if it chooses to.
     * @param packageName the foreground package, taken from the *event* rather than
     *   from a node: nodes inside an embedded WebView can report a different package,
     *   and that would corrupt rate limiting and rule lookup.
     * @param activityName the window's reported class name, if the caller has it.
     * @param nowMs the capture timestamp.
     */
    fun capture(
        root: AccessibilityNodeInfo,
        packageName: String?,
        activityName: String?,
        nowMs: Long,
    ): WindowAnalysis {
        val builder = TreeBuilder()
        try {
            builder.visit(
                node = root,
                parentIndex = -1,
                depth = 0,
                clickableAncestorDepth = null,
                ancestorIds = emptyList(),
                ancestorClasses = emptyList(),
                ancestorTexts = emptyList(),
            )
        } catch (_: Throwable) {
            // Window vanished mid-walk; keep the partial frame.
        }

        val bounds = builder.rootBounds
        val width = if (bounds != null) bounds[2] - bounds[0] else 0
        val height = if (bounds != null) bounds[3] - bounds[1] else 0

        return WindowAnalysis(
            packageName = packageName ?: builder.rootPackageName,
            activityName = activityName ?: builder.rootClassName,
            rootClassName = builder.rootClassName,
            nodes = builder.nodes,
            screenWidth = width.coerceAtLeast(0),
            screenHeight = height.coerceAtLeast(0),
            capturedAtMs = nowMs,
            truncated = builder.truncated,
        )
    }

    /**
     * Raw window-level findings that no single node can express.
     *
     * @property adContainerBounds bounds of every node identified as an ad container.
     */
    data class ProbeResult(
        val hasPaymentContext: Boolean,
        val hasDownloadOrStoreContext: Boolean,
        val hasNestedClickableDescendant: Boolean,
        val candidateIsCentered: Boolean,
        val hasAdCreativeOverlap: Boolean,
        val hasAdDisclosureAnywhere: Boolean,
    ) {
        companion object {
            val NONE = ProbeResult(
                hasPaymentContext = false,
                hasDownloadOrStoreContext = false,
                hasNestedClickableDescendant = false,
                candidateIsCentered = false,
                hasAdCreativeOverlap = false,
                hasAdDisclosureAnywhere = false,
            )
        }
    }

    /**
     * Derives the window-level risk findings. Needs the whole frame, which is why it
     * lives beside the walk rather than in the analysis package.
     *
     * @param candidateIndices indices of the nodes that produced skip-shaped evidence.
     */
    fun probeRisk(analysis: WindowAnalysis, candidateIndices: List<Int>): ProbeResult {
        val nodes = analysis.nodes
        if (nodes.isEmpty()) return ProbeResult.NONE

        val adContainers = nodes.filter { isAdContainer(it) }
        val adContainerBounds = adContainers.map { node ->
            IntBounds(node.boundsLeft, node.boundsTop, node.boundsRight, node.boundsBottom)
        }

        val hasPaymentContext = nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            id.isNotEmpty() && PAYMENT_ID_HINTS.any { it in id }
        }

        val hasDownloadContext = nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            (id.isNotEmpty() && DOWNLOAD_ID_HINTS.any { it in id }) ||
                DOWNLOAD_TEXT_ANY.any { it in node.text }
        }

        val hasNestedClickableDescendant = candidateIndices.any { index ->
            val node = nodes.getOrNull(index)
            node != null && node.descendantClickableCount > 0
        }

        val hasAdCreativeOverlap = candidateIndices.any { index ->
            val node = nodes.getOrNull(index) ?: return@any false
            adContainerBounds.any { container ->
                container.strictlyContains(node) && node.areaPx < container.area
            }
        }

        val candidateIsCentered = candidateIndices.any { index ->
            val node = nodes.getOrNull(index) ?: return@any false
            if (analysis.screenWidth <= 0) {
                false
            } else {
                val centreX = (node.boundsLeft + node.boundsRight) / 2f
                kotlin.math.abs(centreX / analysis.screenWidth - 0.5f) <= 0.15f
            }
        }

        return ProbeResult(
            hasPaymentContext = hasPaymentContext,
            hasDownloadOrStoreContext = hasDownloadContext,
            hasNestedClickableDescendant = hasNestedClickableDescendant,
            candidateIsCentered = candidateIsCentered,
            hasAdCreativeOverlap = hasAdCreativeOverlap,
            hasAdDisclosureAnywhere = adContainers.isNotEmpty(),
        )
    }

    private val DOWNLOAD_TEXT_ANY = listOf("下载", "安装", "应用商店", "download", "install")

    private fun isAdContainer(node: NodeSummary): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.isNotEmpty() && AD_CONTAINER_ID_HINTS.any { it in id }) return true
        val cls = node.className.lowercase()
        return "adview" in cls || "nativead" in cls
    }

    private class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Int get() = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)

        fun strictlyContains(node: NodeSummary): Boolean =
            node.boundsLeft >= left &&
                node.boundsTop >= top &&
                node.boundsRight <= right &&
                node.boundsBottom <= bottom
    }

    /**
     * Accumulator for one walk. Stateful but confined to a single [capture] call, so
     * it is never shared between threads.
     *
     * Ancestor information is threaded down the recursion as parameters rather than
     * looked up afterwards. The previous formulation walked the parent chain once per
     * child, which is quadratic on deep trees; this is a single pass.
     */
    private class TreeBuilder {

        val nodes = ArrayList<NodeSummary>(256)
        var truncated = false
        var rootClassName: String? = null
        var rootPackageName: String? = null
        var rootBounds: IntArray? = null

        fun visit(
            node: AccessibilityNodeInfo,
            parentIndex: Int,
            depth: Int,
            clickableAncestorDepth: Int?,
            ancestorIds: List<String>,
            ancestorClasses: List<String>,
            ancestorTexts: List<String>,
        ) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            if (depth > MAX_DEPTH) {
                truncated = true
                return
            }

            val index = nodes.size
            val summary = summarize(
                node = node,
                index = index,
                parentIndex = parentIndex,
                depth = depth,
                clickableAncestorDepth = clickableAncestorDepth,
                ancestorIds = ancestorIds,
                ancestorClasses = ancestorClasses,
                ancestorTexts = ancestorTexts,
            ) ?: return

            nodes.add(summary)

            if (depth == 0) {
                rootClassName = summary.className
                rootPackageName = summary.packageName
                rootBounds = intArrayOf(
                    summary.boundsLeft, summary.boundsTop, summary.boundsRight, summary.boundsBottom,
                )
            }

            val childCount = safeChildCount(node)

            // Context passed to children. Bounded so a deep tree cannot make the
            // per-node accumulator grow without limit.
            val nextClickableDepth = if (summary.isClickable) 0 else clickableAncestorDepth?.plus(1)
            val nextIds = if (summary.viewIdResourceName.isNullOrEmpty()) ancestorIds else {
                (ancestorIds + summary.viewIdResourceName).takeLast(MAX_ANCESTORS)
            }
            val nextClasses = (ancestorClasses + summary.className).takeLast(MAX_ANCESTORS)
            val nextTexts = if (summary.text.isEmpty()) ancestorTexts else {
                (ancestorTexts + summary.text).takeLast(MAX_ANCESTORS)
            }

            val childIndices = ArrayList<Int>(childCount.coerceAtMost(64))
            var descendantTotal = 0
            var clickableTotal = 0
            var leafTotal = 0

            for (i in 0 until childCount) {
                val child = safeChild(node, i) ?: continue
                val before = nodes.size
                childIndices.add(before)
                visit(
                    node = child,
                    parentIndex = index,
                    depth = depth + 1,
                    clickableAncestorDepth = nextClickableDepth,
                    ancestorIds = nextIds,
                    ancestorClasses = nextClasses,
                    ancestorTexts = nextTexts,
                )
                // Aggregate the subtree this child produced. Children are contiguous
                // in `nodes` beyond `before`, so this stays a single pass.
                val childSummary = nodes.getOrNull(before) ?: continue
                descendantTotal += childSummary.descendantCount + 1
                clickableTotal += childSummary.descendantClickableCount + if (childSummary.isClickable) 1 else 0
                leafTotal += childSummary.leavesUnderNode
            }

            val self = nodes[index]
            val isLeaf = childIndices.isEmpty()
            nodes[index] = self.copy(
                childIndices = childIndices,
                descendantCount = descendantTotal,
                descendantClickableCount = clickableTotal,
                leavesUnderNode = if (isLeaf) 1 else leafTotal,
            )
        }

        private fun summarize(
            node: AccessibilityNodeInfo,
            index: Int,
            parentIndex: Int,
            depth: Int,
            clickableAncestorDepth: Int?,
            ancestorIds: List<String>,
            ancestorClasses: List<String>,
            ancestorTexts: List<String>,
        ): NodeSummary? {
            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: Throwable) {
                return null
            }

            val className = safeString { node.className?.toString() }.orEmpty()
            val classLower = className.lowercase()

            return NodeSummary(
                nodeIndex = index,
                parentIndex = parentIndex,
                childIndices = emptyList(),
                depth = depth,
                text = safeString { node.text?.toString() }.orEmpty().trim().take(MAX_NODE_TEXT),
                contentDescription = safeString { node.contentDescription?.toString() }
                    .orEmpty().trim().take(MAX_NODE_TEXT),
                viewIdResourceName = safeString { node.viewIdResourceName }?.takeIf { it.isNotBlank() },
                className = className,
                packageName = safeString { node.packageName?.toString() },
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
                isClickable = safeBool { node.isClickable },
                isLongClickable = safeBool { node.isLongClickable },
                isEditable = safeBool { node.isEditable },
                isCheckable = safeBool { node.isCheckable },
                isScrollable = safeBool { node.isScrollable },
                isEnabled = safeBool { node.isEnabled },
                isFocusable = safeBool { node.isFocusable },
                isSelected = safeBool { node.isSelected },
                isVisibleToUser = safeBool { node.isVisibleToUser },
                isImportantForAccessibility = safeBool { node.isImportantForAccessibility },
                isPassword = safeBool { node.isPassword },
                isWebView = "webview" in classLower || "chromium" in classLower,
                clickableAncestorDepth = clickableAncestorDepth,
                ancestorIds = ancestorIds,
                ancestorClasses = ancestorClasses,
                ancestorTexts = ancestorTexts,
                // Filled in once the subtree has been walked; see `visit`.
                leavesUnderNode = 0,
                descendantCount = 0,
                descendantClickableCount = 0,
            )
        }

        private fun safeChildCount(node: AccessibilityNodeInfo): Int =
            try {
                node.childCount
            } catch (_: Throwable) {
                0
            }

        private fun safeChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
            try {
                node.getChild(index)
            } catch (_: Throwable) {
                null
            }

        private inline fun safeBool(block: () -> Boolean): Boolean =
            try {
                block()
            } catch (_: Throwable) {
                false
            }

        private inline fun safeString(block: () -> String?): String? =
            try {
                block()
            } catch (_: Throwable) {
                null
            }

        private companion object {
            /**
             * How many ancestor ids/classes/texts to carry down. Two levels of
             * "广告" container is enough to recognise an ad surface, and keeping the
             * accumulator small keeps capture allocation-free in practice.
             */
            const val MAX_ANCESTORS = 4
        }
    }
}
