package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MAX
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MIN

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

private const val LINE = "\n"
private const val ASSIGN = '='

// Enum values are stored by NAME, not ordinal: a preset is a portable document, and reordering an
// enum must never silently turn one modifier into another.
private const val KEY_VERSION = "v"
private const val KEY_EFFECT = "effect"
private const val KEY_EFFECT_EDIT = "effect.edit"
private const val KEY_EFFECT_EDGE_THRESHOLD = "effect.edgeThreshold"
private const val KEY_EFFECT_EDGE_SMOOTHING = "effect.edgeSmoothing"
private const val KEY_EFFECT_EDGE_CONTRAST = "effect.edgeContrast"
private const val KEY_EFFECT_BG_TOLERANCE = "effect.bgTolerance"
private const val KEY_EFFECT_STYLE = "effect.style"
private const val KEY_ICON_SCALE = "iconScale"
private const val KEY_SHAPE = "shape"
private const val KEY_SHAPE_NAME = "shape.name"
private const val KEY_SHAPE_CROP = "shape.crop"
private const val KEY_SHAPE_SCALE = "shape.scale"
private const val KEY_SHAPE_STYLE = "shape.style"
private const val KEY_OUTLINE = "outline"
private const val KEY_OUTLINE_MODE = "outline.mode"
private const val KEY_OUTLINE_WIDTH = "outline.width"
private const val KEY_OUTLINE_STYLE = "outline.style"
private const val KEY_SHADOW = "shadow"
private const val KEY_SHADOW_ENABLED = "shadow.enabled"
private const val KEY_SHADOW_BLUR = "shadow.blur"
private const val KEY_SHADOW_DISTANCE = "shadow.distance"
private const val KEY_SHADOW_ANGLE = "shadow.angle"
private const val KEY_SHADOW_ALL_DIRECTIONS = "shadow.allDirections"
private const val KEY_SHADOW_STYLE = "shadow.style"
private const val KEY_SHADOW_OPACITY = "shadow.opacity"

/**
 * Serialises [payload] as newline-separated `key=value` lines. Deliberately not the encoded form
 * of a UI state object: this is a document other builds must keep reading, so every field is named
 * and the nested colour styles reuse [encodeColorizerStyle] instead of a second gradient format.
 */
fun encodeModifierPreset(payload: ModifierPresetPayload): String = buildList {
    add("$KEY_VERSION$ASSIGN${payload.schemaVersion}")
    payload.effect?.let { effect ->
        add("$KEY_EFFECT${ASSIGN}1")
        add("$KEY_EFFECT_EDIT$ASSIGN${effect.imageEdit.name}")
        add("$KEY_EFFECT_EDGE_THRESHOLD$ASSIGN${effect.edgeThreshold}")
        add("$KEY_EFFECT_EDGE_SMOOTHING$ASSIGN${effect.edgeSmoothing}")
        add("$KEY_EFFECT_EDGE_CONTRAST$ASSIGN${effect.edgeContrast}")
        add("$KEY_EFFECT_BG_TOLERANCE$ASSIGN${effect.backgroundTolerance}")
        add("$KEY_EFFECT_STYLE$ASSIGN${encodeColorizerStyle(effect.colorizerStyle)}")
    }
    payload.iconScale?.let { add("$KEY_ICON_SCALE$ASSIGN$it") }
    payload.shape?.let { shape ->
        add("$KEY_SHAPE${ASSIGN}1")
        add("$KEY_SHAPE_NAME$ASSIGN${shape.shape.name}")
        add("$KEY_SHAPE_CROP$ASSIGN${shape.crop}")
        add("$KEY_SHAPE_SCALE$ASSIGN${shape.scale}")
        add("$KEY_SHAPE_STYLE$ASSIGN${encodeColorizerStyle(shape.style)}")
    }
    payload.outline?.let { outline ->
        add("$KEY_OUTLINE${ASSIGN}1")
        add("$KEY_OUTLINE_MODE$ASSIGN${outline.mode.name}")
        add("$KEY_OUTLINE_WIDTH$ASSIGN${outline.width}")
        add("$KEY_OUTLINE_STYLE$ASSIGN${encodeColorizerStyle(outline.style)}")
    }
    payload.shadow?.let { shadow ->
        add("$KEY_SHADOW${ASSIGN}1")
        add("$KEY_SHADOW_ENABLED$ASSIGN${shadow.enabled}")
        add("$KEY_SHADOW_BLUR$ASSIGN${shadow.blur}")
        add("$KEY_SHADOW_DISTANCE$ASSIGN${shadow.distance}")
        add("$KEY_SHADOW_ANGLE$ASSIGN${shadow.angle}")
        add("$KEY_SHADOW_ALL_DIRECTIONS$ASSIGN${shadow.allDirections}")
        add("$KEY_SHADOW_STYLE$ASSIGN${encodeColorizerStyle(shadow.style)}")
        add("$KEY_SHADOW_OPACITY$ASSIGN${shadow.opacity}")
    }
}.joinToString(LINE)

/**
 * Reads a payload back. Forward compatible on purpose: unknown keys are ignored, a group whose
 * marker is missing stays absent, and a group whose values are damaged falls back to neutral
 * values rather than failing the whole preset. Returns null only when [encoded] carries no
 * recognisable group at all.
 */
fun decodeModifierPreset(encoded: String): ModifierPresetPayload? {
    val values = encoded.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(ASSIGN)
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 1)
        }
        .toMap()
    if (values.isEmpty()) return null

    val effect = if (values[KEY_EFFECT] != null) {
        ModifierPresetEffect(
            imageEdit = values.enumOrNull<ImageEdit>(KEY_EFFECT_EDIT) ?: ImageEdit.NONE,
            edgeThreshold = values.floatIn(KEY_EFFECT_EDGE_THRESHOLD, 0.5f..5f, 2.5f),
            edgeSmoothing = values.floatIn(KEY_EFFECT_EDGE_SMOOTHING, 0.5f..4f, 2f),
            edgeContrast = values[KEY_EFFECT_EDGE_CONTRAST]?.toBooleanStrictOrNull() ?: false,
            backgroundTolerance = values.floatIn(KEY_EFFECT_BG_TOLERANCE, 0f..0.5f, 0.1f),
            colorizerStyle = values.styleOrDefault(KEY_EFFECT_STYLE)
        )
    } else null

    val shape = if (values[KEY_SHAPE] != null) {
        ModifierPresetShape(
            shape = values.enumOrNull<IconShape>(KEY_SHAPE_NAME) ?: IconShape.NONE,
            crop = values[KEY_SHAPE_CROP]?.toBooleanStrictOrNull() ?: true,
            scale = values.floatIn(KEY_SHAPE_SCALE, 0.5f..1.5f, 1f),
            style = values.styleOrDefault(KEY_SHAPE_STYLE)
        )
    } else null

    val outline = if (values[KEY_OUTLINE] != null) {
        ModifierPresetOutline(
            mode = values.enumOrNull<OutlineMode>(KEY_OUTLINE_MODE) ?: OutlineMode.NONE,
            width = values.floatIn(
                KEY_OUTLINE_WIDTH,
                OUTLINE_WIDTH_MIN.toFloat()..OUTLINE_WIDTH_MAX.toFloat(),
                6f
            ),
            style = values.styleOrDefault(KEY_OUTLINE_STYLE)
        )
    } else null

    val shadow = if (values[KEY_SHADOW] != null) {
        ModifierPresetShadow(
            enabled = values[KEY_SHADOW_ENABLED]?.toBooleanStrictOrNull() ?: false,
            blur = values.floatIn(KEY_SHADOW_BLUR, SHADOW_BLUR_MIN..SHADOW_BLUR_MAX, SHADOW_BLUR_DEFAULT),
            distance = values.floatIn(
                KEY_SHADOW_DISTANCE,
                SHADOW_DISTANCE_MIN..SHADOW_DISTANCE_MAX,
                SHADOW_DISTANCE_DEFAULT
            ),
            angle = values.floatIn(KEY_SHADOW_ANGLE, 0f..360f, SHADOW_ANGLE_DEFAULT),
            allDirections = values[KEY_SHADOW_ALL_DIRECTIONS]
                ?.toBooleanStrictOrNull() ?: false,
            style = values.styleOrDefault(KEY_SHADOW_STYLE, android.graphics.Color.BLACK),
            opacity = values.floatIn(
                KEY_SHADOW_OPACITY,
                SHADOW_OPACITY_MIN..SHADOW_OPACITY_MAX,
                SHADOW_OPACITY_DEFAULT
            )
        )
    } else null

    val payload = ModifierPresetPayload(
        schemaVersion = values[KEY_VERSION]?.toIntOrNull() ?: MODIFIER_PRESET_SCHEMA_VERSION,
        effect = effect,
        iconScale = values[KEY_ICON_SCALE]?.toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.5f, 1.5f),
        shape = shape,
        outline = outline,
        shadow = shadow
    )
    return payload.takeIf { !it.isEmpty }
}

private inline fun <reified T : Enum<T>> Map<String, String>.enumOrNull(key: String): T? =
    this[key]?.let { name -> enumValues<T>().firstOrNull { it.name == name } }

private fun Map<String, String>.floatIn(key: String, range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
    this[key]?.toFloatOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(range.start, range.endInclusive)
        ?: fallback

/**
 * Applies only the groups carried by [payload] to source-specific generation options. This is the
 * non-mutating counterpart of the editor mapper and lets preset rows render through the exact same
 * pipeline as Apply without creating a temporary [AdjustmentState].
 */
fun GenerationOptions.withModifierPreset(payload: ModifierPresetPayload): GenerationOptions {
    val plate = payload.shape?.takeIf { it.shape != IconShape.NONE && !it.crop }?.style
    val background = ColorFill(plate?.firstColor ?: bgColor, plate ?: backgroundStyle)
    return copy(
        bgColor = background.color,
        backgroundStyle = background.style,
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

// A damaged or missing style falls back to a plain colour rather than dropping the whole group: the
// user still gets the shape/outline/effect they saved, with a colour they can see and fix.
private fun Map<String, String>.styleOrDefault(
    key: String,
    fallbackColor: Int = android.graphics.Color.WHITE
): ColorizerStyle = this[key]?.let(::decodeColorizerStyle) ?: ColorizerStyle(firstColor = fallbackColor)
