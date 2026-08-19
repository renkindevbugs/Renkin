package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.ModifierPresetEffect
import dev.renkinProject.renkin.icon.creator.ModifierPresetOutline
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.ModifierPresetShape

/**
 * The four groups a preset can carry. They match how the Modifier tab is laid out, so "include
 * Shape" means exactly the block the user was just editing — not a set of fields they have to
 * reason about.
 */
enum class ModifierPresetGroup { EFFECT, ICON_SCALE, SHAPE, OUTLINE }

/**
 * What loading a preset changes outside [AdjustmentState]. The image modifier and the icon colour
 * are hoisted by the edit dialog (they drive the Create tab too), so the mapper reports them back
 * instead of reaching into UI state it does not own. Null = this preset leaves it alone.
 */
data class ModifierPresetApplication(
    val imageEdit: ImageEdit? = null,
    val iconColor: Color? = null
)

/**
 * Reads the transferable half of the editor's current settings.
 *
 * [imageEdit] and [iconColor] come from the dialog because they live above [AdjustmentState].
 * Position, picked background colours, segment layers and brush strokes are never read: they are
 * coordinates and colours of one specific artwork.
 */
internal fun captureModifierPreset(
    adjustments: AdjustmentState,
    imageEdit: ImageEdit,
    iconColor: Color,
    groups: Set<ModifierPresetGroup>
): ModifierPresetPayload = ModifierPresetPayload(
    effect = if (ModifierPresetGroup.EFFECT in groups) {
        ModifierPresetEffect(
            // Segments are picked on one icon's own regions, so a preset carrying them would
            // paint nothing on the next app. The transferable half of that modifier is plain
            // Colorize with the same style.
            imageEdit = if (imageEdit == ImageEdit.COLORIZE_SEGMENTS) ImageEdit.COLORIZE
                else imageEdit,
            edgeThreshold = adjustments.edgeThreshold,
            edgeSmoothing = adjustments.edgeSmoothing,
            edgeContrast = adjustments.edgeContrast,
            backgroundTolerance = adjustments.bgRemovalTolerance,
            colorizerStyle = adjustments.colorizerStyleWith(iconColor)
        )
    } else null,
    iconScale = if (ModifierPresetGroup.ICON_SCALE in groups) adjustments.iconScale else null,
    shape = if (ModifierPresetGroup.SHAPE in groups) {
        ModifierPresetShape(
            shape = adjustments.iconShape,
            crop = adjustments.shapeCrop,
            scale = adjustments.shapeScale,
            style = adjustments.shapeStyle()
        )
    } else null,
    outline = if (ModifierPresetGroup.OUTLINE in groups) {
        ModifierPresetOutline(
            mode = adjustments.outlineMode,
            width = adjustments.outlineWidth,
            style = adjustments.outlineStyle()
        )
    } else null
)

/**
 * Writes [payload] into [adjustments], touching only the groups it carries — everything else the
 * user set on this icon survives. Returns the hoisted values the caller must apply itself.
 */
internal fun applyModifierPreset(
    payload: ModifierPresetPayload,
    adjustments: AdjustmentState
): ModifierPresetApplication {
    var application = ModifierPresetApplication()

    payload.effect?.let { effect ->
        adjustments.edgeThreshold = effect.edgeThreshold
        adjustments.edgeSmoothing = effect.edgeSmoothing
        adjustments.edgeContrast = effect.edgeContrast
        adjustments.bgRemovalTolerance = effect.backgroundTolerance
        adjustments.applyColorizerStyle(effect.colorizerStyle)
        application = application.copy(
            imageEdit = effect.imageEdit,
            iconColor = Color(effect.colorizerStyle.firstColor)
        )
    }
    payload.iconScale?.let { adjustments.iconScale = it }
    payload.shape?.let { shape ->
        adjustments.iconShape = shape.shape
        adjustments.shapeCrop = shape.crop
        adjustments.shapeScale = shape.scale
        adjustments.applyShapeStyle(shape.style)
    }
    payload.outline?.let { outline ->
        adjustments.outlineMode = outline.mode
        adjustments.outlineWidth = outline.width
        adjustments.applyOutlineStyle(outline.style)
    }
    return application
}

/** The groups a freshly opened save sheet ticks: the ones that currently do something. */
internal fun defaultPresetGroups(adjustments: AdjustmentState, imageEdit: ImageEdit): Set<ModifierPresetGroup> =
    buildSet {
        if (imageEdit != ImageEdit.NONE) add(ModifierPresetGroup.EFFECT)
        if (adjustments.iconScale != 1f) add(ModifierPresetGroup.ICON_SCALE)
        if (adjustments.iconShape != dev.renkinProject.renkin.icon.creator.IconShape.NONE) {
            add(ModifierPresetGroup.SHAPE)
        }
        if (adjustments.outlineMode != dev.renkinProject.renkin.icon.creator.OutlineMode.NONE) {
            add(ModifierPresetGroup.OUTLINE)
        }
    }

/** Preserves a preset's original partial-group contract when the user updates it. */
internal fun ModifierPresetPayload.includedGroups(): Set<ModifierPresetGroup> = buildSet {
    if (effect != null) add(ModifierPresetGroup.EFFECT)
    if (iconScale != null) add(ModifierPresetGroup.ICON_SCALE)
    if (shape != null) add(ModifierPresetGroup.SHAPE)
    if (outline != null) add(ModifierPresetGroup.OUTLINE)
}

/**
 * The colourize style as the pipeline sees it: the icon colour is its first stop, which is why the
 * dialog's [iconColor] has to be handed in rather than read from the adjustments.
 */
internal fun AdjustmentState.colorizerStyleWith(iconColor: Color): ColorizerStyle = ColorizerStyle(
    mode = colorizerMode,
    gradientType = colorizerGradientType,
    firstColor = iconColor.toArgb(),
    gradientStops = colorizerGradientColors,
    gradientPositions = colorizerGradientPositions,
    gradientAngle = colorizerGradientAngle,
    flat = colorizeFlat,
    monochrome = colorizeMonochrome,
    inverse = colorizeInverse
)

internal fun AdjustmentState.applyColorizerStyle(style: ColorizerStyle) {
    colorizerMode = style.mode
    colorizerGradientType = style.gradientType
    colorizerGradientColors = style.gradientStops
    colorizerGradientPositions = style.gradientPositions
    colorizerGradientAngle = style.gradientAngle
    colorizeFlat = style.flat
    colorizeMonochrome = style.monochrome
    colorizeInverse = style.inverse
}

internal fun AdjustmentState.shapeStyle(): ColorizerStyle = ColorizerStyle(
    mode = shapeColorizerMode,
    gradientType = shapeGradientType,
    firstColor = shapeColor.toArgb(),
    gradientStops = shapeGradientColors,
    gradientPositions = shapeGradientPositions,
    gradientAngle = shapeGradientAngle
)

internal fun AdjustmentState.applyShapeStyle(style: ColorizerStyle) {
    shapeColorizerMode = style.mode
    shapeGradientType = style.gradientType
    shapeColor = Color(style.firstColor)
    shapeGradientColors = style.gradientStops
    shapeGradientPositions = style.gradientPositions
    shapeGradientAngle = style.gradientAngle
}

internal fun AdjustmentState.outlineStyle(): ColorizerStyle = ColorizerStyle(
    mode = outlineColorizerMode,
    gradientType = outlineGradientType,
    firstColor = outlineColor.toArgb(),
    gradientStops = outlineGradientColors,
    gradientPositions = outlineGradientPositions,
    gradientAngle = outlineGradientAngle
)

internal fun AdjustmentState.applyOutlineStyle(style: ColorizerStyle) {
    outlineColorizerMode = style.mode
    outlineGradientType = style.gradientType
    outlineColor = Color(style.firstColor)
    outlineGradientColors = style.gradientStops
    outlineGradientPositions = style.gradientPositions
    outlineGradientAngle = style.gradientAngle
}

/**
 * The name a new preset is offered: the first free "<prefix> N". Only exact matches of that shape
 * count, so renaming one to something of your own never leaves a gap the next save has to skip.
 * The prefix comes from resources — this stays a pure function so it can be tested.
 */
internal fun defaultModifierPresetName(existing: List<String>, prefix: String): String {
    val taken = existing.mapNotNullTo(mutableSetOf()) { name ->
        name.removePrefix("$prefix ")
            .takeIf { it != name }
            ?.toIntOrNull()
    }
    var candidate = 1
    while (candidate in taken) candidate++
    return "$prefix $candidate"
}
