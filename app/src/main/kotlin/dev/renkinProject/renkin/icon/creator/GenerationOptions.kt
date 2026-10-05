package dev.renkinProject.renkin.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.FALLBACK_SOURCE_DEFAULT
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.FallbackSourceKey
import dev.renkinProject.renkin.data.IMAGE_EDIT_DEFAULT
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.IncludeVectorKey
import dev.renkinProject.renkin.data.MonochromeKey
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_DEFAULT
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.ColorizerStyleKeys
import dev.renkinProject.renkin.data.ColorStyleKeys
import dev.renkinProject.renkin.data.BackgroundStyleKeys
import dev.renkinProject.renkin.data.GlobalShapeStyleKeys
import dev.renkinProject.renkin.data.GlobalColorizerStyleKeys
import dev.renkinProject.renkin.data.OutlineStyleKeys
import dev.renkinProject.renkin.data.OutlineWidthKey
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimaryImageEditKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.PrimaryTextTypeKey
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.SecondaryIconPackKey
import dev.renkinProject.renkin.data.SecondaryImageEditKey
import dev.renkinProject.renkin.data.SecondarySourceKey
import dev.renkinProject.renkin.data.SecondaryTextTypeKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TEXT_TYPE_DEFAULT
import dev.renkinProject.renkin.data.TextFontKey
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getBackgroundColor
import dev.renkinProject.renkin.data.getColorValue
import dev.renkinProject.renkin.data.getIconColor
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.normalizeOutlineWidth
import dev.renkinProject.renkin.data.GlobalColorizeFlatKey
import dev.renkinProject.renkin.data.GlobalColorizeLightenKey
import dev.renkinProject.renkin.data.GlobalColorizeInverseKey
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.GlobalColorizeMonochromeKey
import dev.renkinProject.renkin.data.getGradientStops
import dev.renkinProject.renkin.data.getGradientPositions
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalShapeCropKey
import dev.renkinProject.renkin.data.GlobalShapeKey
import dev.renkinProject.renkin.data.GlobalShapeScaleKey
import dev.renkinProject.renkin.data.normalizeGlobalScalePercent

/** Whether the persisted vector/Material You path options are relevant to this source chain. */
fun arePathOptionsRelevant(
    primarySource: Source,
    primaryImageEdit: ImageEdit,
    secondarySource: Source,
    secondaryImageEdit: ImageEdit
): Boolean =
    (primaryImageEdit == ImageEdit.PATH &&
        (primarySource == Source.ICON_PACK || primarySource == Source.APPLICATION_ICON)) ||
        (primarySource == Source.ICON_PACK && secondaryImageEdit == ImageEdit.PATH &&
            (secondarySource == Source.ICON_PACK || secondarySource == Source.APPLICATION_ICON))

data class GenerationOptions(
    val primarySource: Source,
    val primaryTextType: TextType,
    val primaryIconPack: String,
    val bgColor: Int,
    val vector: Boolean,
    val materialYou: Boolean,
    val themed: Boolean,
    val override: Boolean,
    val modifiers: ModifierStack,
    val secondarySource: Source = Source.NONE,
    val secondaryImageEdit: ImageEdit = ImageEdit.NONE,
    val secondaryTextType: TextType = TextType.FULL_NAME,
    val secondaryIconPack: String = "",
    val backgroundStyle: ColorizerStyle? = null,
    val foregroundStyle: ColorizerStyle? = null,
    val textCustom: String = "",
    val textCase: TextCase = TextCase.AS_IS,
    val textFontPath: String = "",
    val fallbackSource: FallbackSource = FallbackSource.NONE,
    val applicationIconVariant: ApplicationIconVariant = if (materialYou) {
        ApplicationIconVariant.MATERIAL_YOU
    } else {
        ApplicationIconVariant.DEFAULT
    },
    val invertMonochrome: Boolean = false,
    val materialYouPackForeground: Int? = null,
    val materialYouPackBackground: Int? = null,
    val materialYouPackStrokeScale: Float = 1f,
    val materialYouPackSelectedScheme: Int = -1,
    val materialYouPackCustomForeground: ColorizerStyle? = null,
    val materialYouPackCustomBackground: ColorizerStyle? = null,
    val useFullApplicationIcon: Boolean = false
) {
    // Read-only compatibility accessors; modifier ownership lives exclusively in the stack.
    val primaryImageEdit: ImageEdit get() = modifiers.effect.imageEdit
    val color: Int get() = modifiers.effect.colorizer.firstColor
    val edgeLowThreshold: Float get() = modifiers.effect.edges.lowThreshold
    val edgeHighThreshold: Float get() = modifiers.effect.edges.highThreshold
    val edgeGaussianRadius: Float get() = modifiers.effect.edges.smoothing
    val edgeContrastNormalized: Boolean get() = modifiers.effect.edges.normalizeContrast
    val bgRemovalTargets: List<Int> get() = modifiers.effect.backgroundRemoval.targets
    val bgRemovalTolerance: Float get() = modifiers.effect.backgroundRemoval.tolerance
    val backgroundBrushOperations: List<BackgroundBrushOperation> get() = modifiers.effect.backgroundRemoval.brushOperations
    val colorizeLayers: List<SegmentLayer> get() = modifiers.effect.segments
    val iconScale: Float get() = modifiers.transform.scale
    val iconOffsetX: Float get() = modifiers.transform.offsetX
    val iconOffsetY: Float get() = modifiers.transform.offsetY
    val iconShape: IconShape get() = modifiers.shape?.shape ?: IconShape.NONE
    val iconShapeCrop: Boolean get() = modifiers.shape?.crop ?: true
    val iconShapeScale: Float get() = modifiers.shape?.scale ?: 1f
    val outlineMode: OutlineMode get() = modifiers.outline?.mode ?: OutlineMode.NONE
    val outlineWidth: Float get() = modifiers.outline?.width ?: 6f
    val outlineColor: Int get() = modifiers.outline?.fill?.color ?: android.graphics.Color.BLACK
    val outlineStyle: ColorizerStyle? get() = modifiers.outline?.fill?.style
    val outlineEraseMask: android.graphics.Bitmap? get() = modifiers.outline?.eraseMask
    val shadowEnabled: Boolean get() = modifiers.shadow?.enabled == true
    val shadowBlur: Float get() = modifiers.shadow?.blur ?: SHADOW_BLUR_DEFAULT
    val shadowDistance: Float get() = modifiers.shadow?.distance ?: SHADOW_DISTANCE_DEFAULT
    val shadowAngle: Float get() = modifiers.shadow?.angle ?: SHADOW_ANGLE_DEFAULT
    val shadowAllDirections: Boolean get() = modifiers.shadow?.allDirections ?: false
    val shadowStyle: ColorizerStyle get() = modifiers.shadow?.style ?: ColorizerStyle(firstColor = android.graphics.Color.BLACK)
    val shadowOpacity: Float get() = modifiers.shadow?.opacity ?: SHADOW_OPACITY_DEFAULT
    val colorizeFlat: Boolean get() = modifiers.effect.colorizer.flat
    val colorizeLighten: Boolean get() = modifiers.effect.colorizer.lighten
    val colorizeMonochrome: Boolean get() = modifiers.effect.colorizer.monochrome
    val colorizeInverse: Boolean get() = modifiers.effect.colorizer.inverse
    val colorizerMode: ColorizerMode get() = modifiers.effect.colorizer.mode
    val colorizerGradientType: GradientType get() = modifiers.effect.colorizer.gradientType
    val colorizerGradientColors: List<Int> get() = modifiers.effect.colorizer.gradientStops
    val colorizerGradientPositions: List<Float> get() = modifiers.effect.colorizer.gradientPositions
    val colorizerGradientAngle: Float get() = modifiers.effect.colorizer.gradientAngle

    companion object {
        /**
         * Builds the options from the stored preferences. [override] defaults to the
         * user's "override icon" setting (bulk refresh); callers that always override
         * (e.g. generating a single newly installed app) pass `true`.
         *
         * Dynamic (themed) colour resolution stays in the caller, since it only
         * applies to the bulk export path.
         */
        fun fromPreferences(
            preferences: Preferences,
            context: Context,
            override: Boolean = preferences.getBooleanValue(OverrideIconKey)
        ): GenerationOptions {
            val iconColor = preferences.getIconColor(context)
            val bgColor = preferences.getBackgroundColor(context)
            val primarySource = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
            val primaryImageEdit = preferences.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
            val secondarySource = preferences.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
            val secondaryImageEdit = preferences.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
            val pathOptionsRelevant = arePathOptionsRelevant(
                primarySource, primaryImageEdit, secondarySource, secondaryImageEdit
            )

            return GenerationOptions(
                primarySource = primarySource,
                modifiers = ModifierStack(EffectModifier(
                    primaryImageEdit, preferences.colorStyle(ColorizerStyleKeys, iconColor)
                )),
                primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT),
                primaryIconPack = preferences.getStringValue(PrimaryIconPackKey),
                secondarySource = secondarySource,
                secondaryImageEdit = secondaryImageEdit,
                secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT),
                secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey),
                bgColor = bgColor.toArgb(),
                backgroundStyle = preferences.colorStyle(BackgroundStyleKeys, bgColor),
                // Keep the stored choices for when PATH is selected again, but hidden controls
                // must not silently affect a different source/modifier combination.
                vector = pathOptionsRelevant && preferences.getBooleanValue(IncludeVectorKey),
                materialYou = pathOptionsRelevant && preferences.getBooleanValue(MonochromeKey),
                themed = preferences.getBooleanValue(ExportThemedKey),
                override = override,
                fallbackSource = preferences.getEnumValue(FallbackSourceKey, FALLBACK_SOURCE_DEFAULT),
                textFontPath = FontCatalog.usablePathOrDefault(preferences.getStringValue(TextFontKey))
            )
        }
    }
}

/** Final global layer, deliberately separate from primary/secondary source generation. */
fun globalModifierOptions(preferences: Preferences): GenerationOptions {
    val shape = IconShape.entries.getOrNull(
        preferences.getIntValue(GlobalShapeKey, IconShape.NONE.ordinal)
    ) ?: IconShape.NONE
    val shapeCrop = preferences.getBooleanValue(GlobalShapeCropKey, true)
    val colorizerStyle = preferences.colorStyle(
        GlobalColorizerStyleKeys, androidx.compose.ui.graphics.Color.White
    ).copy(
        flat = preferences.getBooleanValue(GlobalColorizeFlatKey),
        lighten = preferences.getBooleanValue(GlobalColorizeLightenKey) &&
            !preferences.getBooleanValue(GlobalColorizeFlatKey) &&
            !preferences.getBooleanValue(GlobalColorizeMonochromeKey),
        monochrome = preferences.getBooleanValue(GlobalColorizeMonochromeKey),
        inverse = preferences.getBooleanValue(GlobalColorizeInverseKey)
    )
    val shapeStyle = if (shape != IconShape.NONE && !shapeCrop) {
        preferences.colorStyle(GlobalShapeStyleKeys, androidx.compose.ui.graphics.Color.White)
    } else null
    val outlineStyle = preferences.colorStyle(
        OutlineStyleKeys, androidx.compose.ui.graphics.Color.Black
    )
    val background = ColorFill(shapeStyle?.firstColor ?: android.graphics.Color.TRANSPARENT, shapeStyle)
    return GenerationOptions(
        primarySource = Source.NONE,
        primaryTextType = TEXT_TYPE_DEFAULT,
        primaryIconPack = "",
        bgColor = background.color,
        backgroundStyle = background.style,
        vector = false,
        materialYou = false,
        themed = false,
        override = true,
        modifiers = ModifierStack(
            effect = EffectModifier(
                if (preferences.getBooleanValue(GlobalColorizeKey)) ImageEdit.COLORIZE else ImageEdit.NONE,
                colorizerStyle
            ),
            transform = TransformModifier(normalizeGlobalScalePercent(
                preferences.getIntValue(GlobalIconScaleKey, 100)
            ) / 100f),
            shape = ShapeModifier(shape, shapeCrop, normalizeGlobalScalePercent(
                preferences.getIntValue(GlobalShapeScaleKey, 100)
            ) / 100f),
            outline = OutlineModifier(
                if (preferences.getBooleanValue(OutlineAddKey)) OutlineMode.ADD else OutlineMode.NONE,
                normalizeOutlineWidth(preferences.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)).toFloat(),
                ColorFill(outlineStyle.firstColor, outlineStyle)
            )
        )
    )
}

/**
 * Any [ColorStyleKeys] group read back as a style. Used for the surfaces whose colour is only a
 * fill — the background and the shape's plate — so they behave like every other colour in the app.
 */
fun Preferences.colorStyle(
    keys: ColorStyleKeys,
    defaultColor: androidx.compose.ui.graphics.Color
): ColorizerStyle = ColorizerStyle(
    mode = getEnumValue(keys.mode, ColorizerMode.SINGLE_COLOR),
    gradientType = getEnumValue(keys.gradientType, GradientType.LINEAR),
    firstColor = getColorValue(keys.firstColor, defaultColor).toArgb(),
    gradientStops = getGradientStops(
        keys.gradientColors, keys.legacyGradientColor ?: keys.firstColor
    ),
    gradientPositions = getGradientPositions(keys.gradientPositions),
    gradientAngle = normalizeGradientAngle(getIntValue(keys.gradientAngle).toFloat())
)

/**
 * The shader [backgroundStyle] asks for, or null when the flat [bgColor] is what to paint. Kept
 * here so every background surface — themed fill, shape plate, previews — asks the same question.
 */
fun GenerationOptions.backgroundShader(width: Int, height: Int): android.graphics.Shader? =
    ColorFill(bgColor, backgroundStyle).shader(width, height)

/** Transfers every colourize field together; callers decide the image edit and segment layers. */
fun GenerationOptions.withColorizerStyle(style: ColorizerStyle): GenerationOptions = copy(
    modifiers = modifiers.copy(effect = modifiers.effect.copy(colorizer = style))
)

internal val GenerationOptions.colorizerStyle: ColorizerStyle get() = modifiers.effect.colorizer

// Colorize blend for bitmap icons: SRC_IN replaces the icon's colours with the picked one (flat
// fill), SCREEN lightens toward it, MULTIPLY tints them. Vectors recolour flat unless baked.
internal val GenerationOptions.colorizeBlendMode: android.graphics.PorterDuff.Mode
    get() = modifiers.effect.colorizer.blendMode

// Position, scale, shape, outline and shadow: everything [IconAdjustmentPipeline] applies after
// the image edit. Callers that skip work when nothing changes must all agree on this list.
fun GenerationOptions.hasIconAdjustments(): Boolean = modifiers.hasAdjustments

fun GenerationOptions.hasVisibleModifierEffect(): Boolean =
    modifiers.effect.imageEdit != ImageEdit.NONE || modifiers.hasAdjustments ||
        materialYouPackForeground != null || materialYouPackBackground != null ||
        materialYouPackStrokeScale != 1f

fun GenerationOptions.hasVisibleShadow(): Boolean = modifiers.shadow?.isVisible == true

/** Letter-case transform for text icons (per-app option; not persisted globally). */
enum class TextCase { AS_IS, UPPER, LOWER }

/** How an app's own launcher icon is represented in the per-app editor. */
enum class ApplicationIconVariant { DEFAULT, MATERIAL_YOU, MONOCHROME }
