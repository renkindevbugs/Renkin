package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.ImageEdit

/**
 * A reusable Modifier-tab recipe, independent of any one icon.
 *
 * Only settings that mean the same thing on a different app live here. Everything the user picked
 * ON a specific icon — its position, the colours picked for background removal, segment layers and
 * every brush stroke — is deliberately absent: those describe pixels of that artwork and would be
 * meaningless (or destructive) elsewhere.
 *
 * A null group means "this preset does not carry that group": applying it leaves the icon's own
 * settings for that group untouched. A group that IS present but holds a neutral value (no image
 * effect, no shape, no outline or no shadow) is a deliberate "turn this off", which is why
 * inclusion is modelled as presence rather than as "differs from default".
 */
data class ModifierPresetPayload(
    val schemaVersion: Int = MODIFIER_PRESET_SCHEMA_VERSION,
    val effect: ModifierPresetEffect? = null,
    val iconScale: Float? = null,
    val shape: ModifierPresetShape? = null,
    val outline: ModifierPresetOutline? = null,
    val shadow: ModifierPresetShadow? = null
) {
    /** True when the preset would change nothing at all — the save button gates on this. */
    val isEmpty: Boolean
        get() = effect == null && iconScale == null && shape == null && outline == null && shadow == null
}

/**
 * The image modifier and every parameter that travels with it. The colour lives in
 * [colorizerStyle] because Colorize reads its first colour as the icon colour, and the same style
 * carries gradients and the solid/monochrome/inverse flags.
 */
data class ModifierPresetEffect(
    val imageEdit: ImageEdit,
    val edgeThreshold: Float,
    val edgeSmoothing: Float,
    val edgeContrast: Boolean,
    // Only the automatic tolerance travels: the picked colours belong to one icon's artwork.
    val backgroundTolerance: Float,
    val colorizerStyle: ColorizerStyle
)

/** The shape plate: which shape, whether it crops or sits behind, its scale and its fill. */
data class ModifierPresetShape(
    val shape: IconShape,
    val crop: Boolean,
    val scale: Float,
    val style: ColorizerStyle
)

/** The outline: add or recolor, how thick, and the colour or gradient it is drawn with. */
data class ModifierPresetOutline(
    val mode: OutlineMode,
    val width: Float,
    val style: ColorizerStyle
)

/** A shadow drawn from the final icon silhouette. */
data class ModifierPresetShadow(
    val enabled: Boolean,
    val blur: Float,
    val distance: Float,
    val angle: Float,
    val allDirections: Boolean,
    val style: ColorizerStyle,
    val opacity: Float
)

/**
 * Bumped only when a stored payload can no longer be read by [decodeModifierPreset] as written.
 * Adding a new optional line does NOT need a bump: unknown keys are ignored on read and missing
 * ones fall back, so an older build reading a newer payload simply drops what it cannot use.
 */
const val MODIFIER_PRESET_SCHEMA_VERSION = 1

/** Preset v1 remains readable by older builds; groups use the shared recipe codec. */
fun encodeModifierPreset(payload: ModifierPresetPayload): String = ModifierRecipeCodec.encodePreset(payload)

/** Missing groups stay absent; damaged values retain the preset's neutral defaults. */
fun decodeModifierPreset(encoded: String): ModifierPresetPayload? = ModifierRecipeCodec.decodePreset(encoded)

/**
 * Applies only the groups carried by [payload] to source-specific generation options. This is the
 * non-mutating counterpart of the editor mapper and lets preset rows render through the exact same
 * pipeline as Apply without creating a temporary [AdjustmentState].
 */
fun GenerationOptions.withModifierPreset(payload: ModifierPresetPayload): GenerationOptions {
    val plate = payload.shape?.takeIf { it.shape != IconShape.NONE && !it.crop }?.style
    val background = ColorFill(plate?.firstColor ?: export.background.color, plate ?: export.background.style)
    return copy(
        export = export.copy(background = background),
        modifiers = modifiers.withModifierPreset(payload)
    )
}

internal fun ModifierStack.withModifierPreset(payload: ModifierPresetPayload): ModifierStack {
    val newEffect = payload.effect?.let { preset ->
        effect.copy(
            imageEdit = preset.imageEdit,
            colorizer = preset.colorizerStyle,
            edges = EdgeModifier(preset.edgeThreshold, preset.edgeThreshold * 3f, preset.edgeSmoothing, preset.edgeContrast),
            backgroundRemoval = effect.backgroundRemoval.copy(tolerance = preset.backgroundTolerance),
            segments = emptyList()
        )
    } ?: effect
    return copy(
        effect = newEffect,
        transform = payload.iconScale?.let { transform.copy(scale = it) } ?: transform,
        shape = payload.shape?.let { ShapeModifier(it.shape, it.crop, it.scale) } ?: shape,
        outline = payload.outline?.let {
            OutlineModifier(it.mode, it.width, ColorFill(it.style.firstColor, it.style), outline?.eraseMask)
        } ?: outline,
        shadow = payload.shadow?.let {
            ShadowModifier(it.blur, it.distance, it.angle, it.allDirections, it.style, it.opacity, it.enabled)
        } ?: shadow
    )
}
