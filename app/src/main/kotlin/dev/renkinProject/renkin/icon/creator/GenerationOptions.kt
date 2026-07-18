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
import dev.renkinProject.renkin.data.OutlineColorKey
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
import dev.renkinProject.renkin.data.GlobalColorizeColorKey
import dev.renkinProject.renkin.data.GlobalColorizeFlatKey
import dev.renkinProject.renkin.data.GlobalColorizeInverseKey
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.GlobalColorizeMonochromeKey
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalShapeColorKey
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

// The secondary* fields default to "no secondary source", so a single-source
// caller can omit them entirely. They sit at the end so positional construction
// (primary source + the shared options) stays terse.
data class GenerationOptions(
    val primarySource: Source,
    val primaryImageEdit: ImageEdit,
    val primaryTextType: TextType,
    val primaryIconPack: String,
    val color: Int,
    val bgColor: Int,
    val vector: Boolean,
    val materialYou: Boolean,
    val themed: Boolean,
    val override: Boolean,
    val edgeLowThreshold: Float = 2.5F,
    val edgeHighThreshold: Float = 7.5F,
    val edgeGaussianRadius: Float = 2F,
    val edgeContrastNormalized: Boolean = false,
    val secondarySource: Source = Source.NONE,
    val secondaryImageEdit: ImageEdit = ImageEdit.NONE,
    val secondaryTextType: TextType = TextType.FULL_NAME,
    val secondaryIconPack: String = "",
    // Per-icon adjustments from the Modifier tab (not part of the bulk preferences).
    // iconScale 1f = unchanged; < 1f pads the icon inside its frame.
    val iconScale: Float = 1f,
    // Position nudge as a fraction of the icon size: -0.5..0.5. Auto-centre in the Position dialog
    // just computes these, so the offsets are the single source of truth for the icon's position.
    val iconOffsetX: Float = 0f,
    val iconOffsetY: Float = 0f,
    // Colour-distance tolerance (0..1) for the Remove background modifier — how far a pixel's colour
    // can be from the border background colour and still be erased.
    val bgRemovalTolerance: Float = 0.1f,
    // Colorize as a flat fill (SRC_IN) rather than the default multiply blend, so the picked colour
    // replaces the icon's own colours instead of mixing with them. Per-icon Modifier-tab option.
    val colorizeFlat: Boolean = false,
    // Alternative Colorize results: grayscale, plus optional inversion of either grayscale or RGB.
    val colorizeMonochrome: Boolean = false,
    val colorizeInverse: Boolean = false,
    // Icon shape applied as the LAST step: NONE leaves the icon untouched; otherwise the icon
    // is cropped into the shape (the default — most icons are full-bleed) or laid on a
    // [bgColor]-filled shape plate. [iconShapeScale] sizes the SHAPE itself (the icon stays
    // as-is — that's [iconScale]): smaller crops deeper, larger clips just the corners.
    val iconShape: IconShape = IconShape.NONE,
    val iconShapeCrop: Boolean = true,
    val iconShapeScale: Float = 1f,
    // Outline (Modifier tab, applied after scale/offset and before the shape): ADD draws a
    // [outlineColor] contour of [outlineWidth] px (at the 256px working size) around the
    // icon's silhouette; RECOLOR repaints the icon's existing boundary ring instead.
    val outlineMode: OutlineMode = OutlineMode.NONE,
    val outlineWidth: Float = 6f,
    val outlineColor: Int = android.graphics.Color.BLACK,
    // Painted areas where the outline step must not apply (the eraser tool). Alpha mask in
    // normalised icon space; null = outline everywhere. Session-only — never persisted.
    val outlineEraseMask: android.graphics.Bitmap? = null,
    // Text-icon options: the string rendered for TextType.CUSTOM (empty falls back to the app
    // name), the letter-case transform (all text types), and the TTF/OTF the glyphs come from
    // (empty = the bundled Arcticons Sans).
    val textCustom: String = "",
    val textCase: TextCase = TextCase.AS_IS,
    val textFontPath: String = "",
    // Which pack's fallback styling to give apps neither pack themes (NONE = leave them raw).
    val fallbackSource: FallbackSource = FallbackSource.NONE,
    // Per-app Application Icon choice. Existing bulk preferences keep their old behaviour by
    // mapping the persisted monochrome flag to the Material You layer.
    val applicationIconVariant: ApplicationIconVariant = if (materialYou) {
        ApplicationIconVariant.MATERIAL_YOU
    } else {
        ApplicationIconVariant.DEFAULT
    },
    // Per-app Monochrome option: invert luminance after desaturation (black ↔ white).
    val invertMonochrome: Boolean = false
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

            return GenerationOptions(
                primarySource = primarySource,
                primaryImageEdit = primaryImageEdit,
                primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT),
                primaryIconPack = preferences.getStringValue(PrimaryIconPackKey),
                secondarySource = secondarySource,
                secondaryImageEdit = secondaryImageEdit,
                secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT),
                secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey),
                color = iconColor.toArgb(),
                bgColor = bgColor.toArgb(),
                // Keep the stored choices for when PATH is selected again, but hidden controls
                // must not silently affect a different source/modifier combination.
                vector = pathOptionsRelevant && preferences.getBooleanValue(IncludeVectorKey),
                materialYou = pathOptionsRelevant && preferences.getBooleanValue(MonochromeKey),
                themed = preferences.getBooleanValue(ExportThemedKey),
                override = override,
                fallbackSource = preferences.getEnumValue(FallbackSourceKey, FALLBACK_SOURCE_DEFAULT),
                textFontPath = FontCatalog.usablePathOrDefault(preferences.getStringValue(TextFontKey)),
                // Only ADD exists pack-wide; RECOLOR stays a per-app Modifier-tab option.
                outlineMode = OutlineMode.NONE
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
    return GenerationOptions(
        primarySource = Source.NONE,
        primaryImageEdit = if (preferences.getBooleanValue(GlobalColorizeKey)) {
            ImageEdit.COLORIZE
        } else ImageEdit.NONE,
        primaryTextType = TEXT_TYPE_DEFAULT,
        primaryIconPack = "",
        color = preferences.getColorValue(
            GlobalColorizeColorKey, androidx.compose.ui.graphics.Color.White
        ).toArgb(),
        bgColor = if (shape != IconShape.NONE && !shapeCrop) {
            preferences.getColorValue(
                GlobalShapeColorKey, androidx.compose.ui.graphics.Color.White
            ).toArgb()
        } else android.graphics.Color.TRANSPARENT,
        vector = false,
        materialYou = false,
        themed = false,
        override = true,
        colorizeFlat = preferences.getBooleanValue(GlobalColorizeFlatKey),
        colorizeMonochrome = preferences.getBooleanValue(GlobalColorizeMonochromeKey),
        colorizeInverse = preferences.getBooleanValue(GlobalColorizeInverseKey),
        iconScale = normalizeGlobalScalePercent(
            preferences.getIntValue(GlobalIconScaleKey, 100)
        ) / 100f,
        iconShape = shape,
        iconShapeCrop = shapeCrop,
        iconShapeScale = normalizeGlobalScalePercent(
            preferences.getIntValue(GlobalShapeScaleKey, 100)
        ) / 100f,
        outlineMode = if (preferences.getBooleanValue(OutlineAddKey)) {
            OutlineMode.ADD
        } else OutlineMode.NONE,
        outlineWidth = normalizeOutlineWidth(
            preferences.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)
        ).toFloat(),
        outlineColor = preferences.getColorValue(
            OutlineColorKey, androidx.compose.ui.graphics.Color.Black
        ).toArgb()
    )
}

fun GenerationOptions.hasVisibleModifierEffect(): Boolean =
    primaryImageEdit != ImageEdit.NONE || iconScale != 1f ||
        iconShape != IconShape.NONE || outlineMode != OutlineMode.NONE

/** Letter-case transform for text icons (per-app option; not persisted globally). */
enum class TextCase { AS_IS, UPPER, LOWER }

/** How an app's own launcher icon is represented in the per-app editor. */
enum class ApplicationIconVariant { DEFAULT, MATERIAL_YOU, MONOCHROME }
