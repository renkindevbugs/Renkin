package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.plateFill
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.SHADOW_ANGLE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_DEFAULT
import android.graphics.Bitmap
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.BackgroundBrushOperation
import dev.renkinProject.renkin.icon.creator.BackgroundRemovalModifier
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.EdgeModifier
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.OutlineModifier
import dev.renkinProject.renkin.icon.creator.ShadowModifier
import dev.renkinProject.renkin.icon.creator.ShapeModifier
import dev.renkinProject.renkin.icon.creator.TransformModifier

/**
 * The Modifier tab's per-icon adjustment state (edge tuning, background-removal tolerance, scale,
 * position), shared by both editors. Each colour group owns one immutable style.
 * The individual editor session retains this live state across configuration changes.
 */
@Stable
internal class AdjustmentState {
    // The first Colorize stop is supplied by the dialog's shared iconColor at render time.
    var colorizerStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.WHITE))
    var shapeStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.WHITE))
    var outlineStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.BLACK))
    var shadowStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.BLACK))

    var edgeThreshold by mutableFloatStateOf(2.5f)
    var edgeSmoothing by mutableFloatStateOf(2f)
    var edgeContrast by mutableStateOf(false)
    // Per-region colourize steps of the Colorize segments modifier, applied in order.
    var colorizeLayers by mutableStateOf(emptyList<SegmentLayer>())
    var iconScale by mutableFloatStateOf(1f)
    // Colours the user picked to drop; empty leaves the automatic border flood in charge.
    var bgRemovalTargets by mutableStateOf(emptyList<Int>())
    var bgRemovalTolerance by mutableFloatStateOf(0.1f)
    // Auto-center is UI state only: switching it on computes the offsets below (the pipeline's
    // single source of truth for position); dragging a position slider switches it back off.
    var autoCenter by mutableStateOf(false)
    var iconOffsetX by mutableFloatStateOf(0f)
    var iconOffsetY by mutableFloatStateOf(0f)
    // Icon shape: crop-vs-plate mode
    // (crop is the default — most icons are full-bleed), the icon's size relative to
    // the shape, and the plate's fill colour.
    var iconShape by mutableStateOf(IconShape.NONE)
    var shapeCrop by mutableStateOf(true)
    var shapeScale by mutableFloatStateOf(1f)
    // Outline: add a contour around the silhouette, or recolor the icon's existing one.
    var outlineMode by mutableStateOf(OutlineMode.NONE)
    var outlineWidth by mutableFloatStateOf(6f)
    var shadowEnabled by mutableStateOf(false)
    var shadowBlur by mutableFloatStateOf(SHADOW_BLUR_DEFAULT)
    var shadowDistance by mutableFloatStateOf(SHADOW_DISTANCE_DEFAULT)
    var shadowAngle by mutableFloatStateOf(SHADOW_ANGLE_DEFAULT)
    var shadowAllDirections by mutableStateOf(false)
    var shadowOpacity by mutableFloatStateOf(SHADOW_OPACITY_DEFAULT)
    // Brush geometry stays with the live session and is not part of portable recipes.
    var eraseStrokes by mutableStateOf<List<BrushStroke>>(emptyList())
    // Hand corrections to the background removal, same reasoning: transient per-app geometry.
    var backgroundBrushStrokes by mutableStateOf<List<BrushStroke>>(emptyList())
}

internal fun AdjustmentState.applyToModifierStack(
    stack: ModifierStack,
    imageEdit: ImageEdit,
    iconColor: Int,
    outlineEraseMask: Bitmap?,
    backgroundBrushOperations: List<BackgroundBrushOperation>
): ModifierStack = stack.copy(
    effect = EffectModifier(
        imageEdit = imageEdit,
        colorizer = colorizerStyle.copy(firstColor = iconColor),
        edges = EdgeModifier(edgeThreshold, edgeThreshold * 3f, edgeSmoothing, edgeContrast),
        backgroundRemoval = BackgroundRemovalModifier(
            bgRemovalTargets, bgRemovalTolerance,
            backgroundBrushOperations.takeIf { imageEdit == ImageEdit.REMOVE_BACKGROUND } ?: emptyList()
        ),
        segments = colorizeLayers.takeIf { imageEdit == ImageEdit.COLORIZE_SEGMENTS } ?: emptyList()
    ),
    transform = TransformModifier(iconScale, iconOffsetX, iconOffsetY),
    shape = ShapeModifier(iconShape, shapeCrop, shapeScale, plateFill(iconShape, shapeCrop, shapeStyle)),
    outline = OutlineModifier(outlineMode, outlineWidth, ColorFill(outlineStyle.firstColor, outlineStyle), outlineEraseMask),
    shadow = shadowModifier()
)

internal fun AdjustmentState.shadowModifier(): ShadowModifier = ShadowModifier(
    shadowBlur, shadowDistance, shadowAngle, shadowAllDirections, shadowStyle, shadowOpacity, shadowEnabled
)
