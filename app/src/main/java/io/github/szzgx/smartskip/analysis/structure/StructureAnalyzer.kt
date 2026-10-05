package io.github.szzgx.smartskip.analysis.structure

import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.position.PositionAnalyzer

/**
 * Structural evidence: what kind of view the candidate is, and how it behaves
 * across frames.
 *
 * This is the layer that separates a real skip *button* from the word "跳过"
 * printed inside an advertisement, a tutorial bubble, or a piece of body text.
 *
 * @property isLeafLike the node has few enough descendants to plausibly be a control.
 * @property isContainerLike the node has too many descendants to be a control.
 * @property isActionable the node or an ancestor will actually accept a click.
 * @property isTextField an editable field; never a skip button.
 * @property isSelectionControl a checkbox/switch; never auto-tapped.
 * @property sizeIsStable the control kept the same geometry across recent frames.
 * @property hasBeenClickable whether this signature was ever seen clickable, which
 *   catches click listeners attached a frame after the view appears.
 * @property isHiddenFromAccessibility the node is flagged unimportant; ad SDKs use
 *   this to hide their skip button from tools, so it is *mildly* suspicious.
 * @property looksLikeButtonClass the view class is a button-ish widget.
 * @property areaFraction share of the window the node covers.
 */
data class StructureEvidence(
    val isLeafLike: Boolean,
    val isContainerLike: Boolean,
    val isActionable: Boolean,
    val isTextField: Boolean,
    val isSelectionControl: Boolean,
    val sizeIsStable: Boolean,
    val hasBeenClickable: Boolean,
    val isHiddenFromAccessibility: Boolean,
    val looksLikeButtonClass: Boolean,
    val areaFraction: Float,
    val isFullscreen: Boolean,
)

/**
 * Derives [StructureEvidence] from a [CandidateContext].
 */
object StructureAnalyzer {

    /** A control rarely has more than this many descendants. */
    private const val CONTAINER_LEAF_LIMIT = 6

    /** View-class name fragments that indicate a button-like widget. */
    private val BUTTON_CLASS_HINTS = listOf(
        "button", "imagebutton", "textview", "imageview", "appcompattextview",
        "materialbutton", "viewgroup", "framelayout", "relativelayout", "linearlayout",
        "constraintlayout", "customview", "skipview",
    )

    fun analyze(ctx: CandidateContext): StructureEvidence {
        val areaFraction = ctx.screenAreaFraction
        val fullscreen = PositionAnalyzer.isFullscreen(areaFraction)

        val leafLike = ctx.leavesUnderNode in 0..CONTAINER_LEAF_LIMIT
        val containerLike = ctx.leavesUnderNode > CONTAINER_LEAF_LIMIT || fullscreen

        // An ancestor being clickable is normal and sufficient: many Android UIs
        // put the listener on a wrapper and let the TextView be inert.
        val actionable = (ctx.isClickable || ctx.isLongClickable || ctx.clickableAncestorDepth != null) &&
            ctx.isEnabled

        val className = ctx.className.lowercase()
        val looksLikeButton = BUTTON_CLASS_HINTS.any { it in className }

        return StructureEvidence(
            isLeafLike = leafLike,
            isContainerLike = containerLike,
            isActionable = actionable,
            isTextField = ctx.isEditable,
            isSelectionControl = ctx.isCheckable,
            sizeIsStable = ctx.sameSizeSamples >= 2,
            hasBeenClickable = ctx.clickabilityEverObserved,
            isHiddenFromAccessibility = !ctx.isImportantForAccessibility,
            looksLikeButtonClass = looksLikeButton,
            areaFraction = areaFraction,
            isFullscreen = fullscreen,
        )
    }
}
