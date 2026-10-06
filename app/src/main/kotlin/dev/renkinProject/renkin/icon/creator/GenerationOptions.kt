package dev.renkinProject.renkin.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.FALLBACK_SOURCE_DEFAULT
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
    val source: IconSourceOptions,
    val modifiers: ModifierStack,
    val export: IconExportOptions,
    val override: Boolean
) {
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

            val materialYou = pathOptionsRelevant && preferences.getBooleanValue(MonochromeKey)
            return GenerationOptions(
                source = IconSourceOptions(
                    primarySource = primarySource,
                    primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT),
                    primaryIconPack = preferences.getStringValue(PrimaryIconPackKey),
                    secondarySource = secondarySource,
                    secondaryImageEdit = secondaryImageEdit,
                    secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT),
                    secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey),
                    fallbackSource = preferences.getEnumValue(FallbackSourceKey, FALLBACK_SOURCE_DEFAULT),
                    textFontPath = FontCatalog.usablePathOrDefault(preferences.getStringValue(TextFontKey)),
                    applicationIconVariant = if (materialYou) {
                        ApplicationIconVariant.MATERIAL_YOU
                    } else ApplicationIconVariant.DEFAULT
                ),
                export = IconExportOptions(
                    vector = pathOptionsRelevant && preferences.getBooleanValue(IncludeVectorKey),
                    materialYou = materialYou,
                    themed = preferences.getBooleanValue(ExportThemedKey),
                    background = ColorFill(
                        color = bgColor.toArgb(),
                        style = preferences.colorStyle(BackgroundStyleKeys, bgColor)
                    )
                ),
                modifiers = ModifierStack(EffectModifier(
                    primaryImageEdit, preferences.colorStyle(ColorizerStyleKeys, iconColor)
                )),
                override = override
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
        lighten = preferences.getBooleanValue(GlobalColorizeLightenKey),
        monochrome = preferences.getBooleanValue(GlobalColorizeMonochromeKey),
        inverse = preferences.getBooleanValue(GlobalColorizeInverseKey)
    ).normalizedFlags()
    val shapeStyle = if (shape != IconShape.NONE && !shapeCrop) {
        preferences.colorStyle(GlobalShapeStyleKeys, androidx.compose.ui.graphics.Color.White)
    } else null
    val outlineStyle = preferences.colorStyle(
        OutlineStyleKeys, androidx.compose.ui.graphics.Color.Black
    )
    val background = ColorFill(shapeStyle?.firstColor ?: android.graphics.Color.TRANSPARENT, shapeStyle)
    return GenerationOptions(
        source = IconSourceOptions(
            primarySource = Source.NONE,
            primaryTextType = TEXT_TYPE_DEFAULT,
            primaryIconPack = ""
        ),
        export = IconExportOptions(
            vector = false,
            materialYou = false,
            themed = false,
            background = background
        ),
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
 * The shader the export background asks for, or null when its flat colour is what to paint. Kept
 * here so every background surface — themed fill, shape plate, previews — asks the same question.
 */
fun GenerationOptions.backgroundShader(width: Int, height: Int): android.graphics.Shader? =
    export.background.shader(width, height)

/** Transfers every colourize field together; callers decide the image edit and segment layers. */
fun GenerationOptions.withColorizerStyle(style: ColorizerStyle): GenerationOptions =
    withModifiers { withEffect { copy(colorizer = style) } }

// Position, scale, shape, outline and shadow: everything [IconAdjustmentPipeline] applies after
// the image edit. Callers that skip work when nothing changes must all agree on this list.
fun GenerationOptions.hasIconAdjustments(): Boolean = modifiers.hasAdjustments

fun GenerationOptions.hasVisibleModifierEffect(): Boolean = modifiers.hasVisibleEffect

fun GenerationOptions.hasVisibleShadow(): Boolean = modifiers.shadow?.isVisible == true

inline fun GenerationOptions.withSource(update: IconSourceOptions.() -> IconSourceOptions): GenerationOptions =
    copy(source = source.update())

inline fun GenerationOptions.withModifiers(update: ModifierStack.() -> ModifierStack): GenerationOptions =
    copy(modifiers = modifiers.update())

inline fun GenerationOptions.withExport(update: IconExportOptions.() -> IconExportOptions): GenerationOptions =
    copy(export = export.update())
