package io.github.szzgx.smartskip.analysis.position

/**
 * Coarse screen regions.
 *
 * Splash-ad skip controls live in predictable places: the top-right corner on
 * almost every Chinese ad SDK (穿山甲/优量汇/快手), occasionally top-left, and
 * the bottom-right for banner-style interstitials. Ad *creative* reliably owns
 * the centre. Region is therefore a strong prior, but — per design doc §7.1 — a
 * prior only, never a verdict.
 */
enum class ScreenRegion {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    MIDDLE_LEFT,
    CENTER,
    MIDDLE_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    /** The node fills so much of the window that "position" is meaningless. */
    FULLSCREEN,
}

/**
 * Position-derived evidence.
 *
 * @property horizontalBias `0f` at the left edge, `1f` at the right edge.
 * @property verticalBias `0f` at the top edge, `1f` at the bottom edge.
 * @property cornerAffinity how corner-like the placement is, `0f`..`1f`. Corner
 *   placement plus small size is the canonical skip-button geometry.
 * @property isInsideTopRightQuadrant convenience flag for the most common case.
 * @property isHorizontallyCentered true when the node sits near the horizontal
 *   centre, which correlates with creative rather than chrome.
 */
data class PositionEvidence(
    val region: ScreenRegion,
    val horizontalBias: Float,
    val verticalBias: Float,
    val cornerAffinity: Float,
    val isInsideTopRightQuadrant: Boolean,
    val isHorizontallyCentered: Boolean,
)

/**
 * Classifies where on the screen the candidate sits.
 */
object PositionAnalyzer {

    /** A node covering more than this fraction of the window is not "placed". */
    private const val FULLSCREEN_AREA_FRACTION = 0.62f

    /** How close to an edge (as a fraction of the axis) still counts as the edge. */
    private const val EDGE_FRACTION = 0.22f

    /** How close to the centre still counts as centred. */
    private const val CENTER_BAND = 0.30f

    fun analyze(centerX: Float, centerY: Float, width: Float, height: Float): PositionEvidence {
        if (width <= 0f || height <= 0f) {
            return PositionEvidence(
                region = ScreenRegion.CENTER,
                horizontalBias = 0.5f,
                verticalBias = 0.5f,
                cornerAffinity = 0f,
                isInsideTopRightQuadrant = false,
                isHorizontallyCentered = true,
            )
        }

        val hxBias = (centerX / width).coerceIn(0f, 1f)
        val vBias = (centerY / height).coerceIn(0f, 1f)

        val nearLeft = hxBias <= EDGE_FRACTION
        val nearRight = hxBias >= 1f - EDGE_FRACTION
        // "Centred" is a statement about the horizontal axis only; vertical
        // position is captured separately by `verticalBias` and `region`.
        val centeredX = kotlin.math.abs(hxBias - 0.5f) <= CENTER_BAND / 2f && !nearLeft && !nearRight
        val nearTop = vBias <= EDGE_FRACTION
        val nearBottom = vBias >= 1f - EDGE_FRACTION

        val region = when {
            nearTop && nearLeft -> ScreenRegion.TOP_LEFT
            nearTop && nearRight -> ScreenRegion.TOP_RIGHT
            nearTop -> ScreenRegion.TOP_CENTER
            nearBottom && nearLeft -> ScreenRegion.BOTTOM_LEFT
            nearBottom && nearRight -> ScreenRegion.BOTTOM_RIGHT
            nearBottom -> ScreenRegion.BOTTOM_CENTER
            nearLeft -> ScreenRegion.MIDDLE_LEFT
            nearRight -> ScreenRegion.MIDDLE_RIGHT
            else -> ScreenRegion.CENTER
        }

        // Corner affinity: how much the node hugs a corner. Uses the distance to
        // the nearest corner in normalised screen space, so it is resolution
        // independent.
        val dx = minOf(hxBias, 1f - hxBias)
        val dy = minOf(vBias, 1f - vBias)
        val cornerDistance = kotlin.math.sqrt(dx * dx + dy * dy)
        // 0 at a corner, ~0.707 at the centre; remap so a true corner is 1 and the
        // middle is 0.
        val cornerAffinity = (1f - (cornerDistance / 0.7071f)).coerceIn(0f, 1f)

        return PositionEvidence(
            region = region,
            horizontalBias = hxBias,
            verticalBias = vBias,
            cornerAffinity = cornerAffinity,
            isInsideTopRightQuadrant = hxBias >= 0.5f && vBias <= 0.5f,
            isHorizontallyCentered = centeredX,
        )
    }

    /** True when the node's area makes positional reasoning meaningless. */
    fun isFullscreen(areaFraction: Float): Boolean = areaFraction >= FULLSCREEN_AREA_FRACTION
}
