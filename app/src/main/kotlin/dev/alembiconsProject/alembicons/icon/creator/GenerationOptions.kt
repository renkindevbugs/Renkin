package dev.alembiconsProject.alembicons.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.FALLBACK_SOURCE_DEFAULT
import dev.alembiconsProject.alembicons.data.FallbackSource
import dev.alembiconsProject.alembicons.data.FallbackSourceKey
import dev.alembiconsProject.alembicons.data.IMAGE_EDIT_DEFAULT
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.IncludeVectorKey
import dev.alembiconsProject.alembicons.data.MonochromeKey
import dev.alembiconsProject.alembicons.data.OverrideIconKey
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.PrimaryImageEditKey
import dev.alembiconsProject.alembicons.data.PrimarySourceKey
import dev.alembiconsProject.alembicons.data.PrimaryTextTypeKey
import dev.alembiconsProject.alembicons.data.SOURCE_DEFAULT
import dev.alembiconsProject.alembicons.data.SecondaryIconPackKey
import dev.alembiconsProject.alembicons.data.SecondaryImageEditKey
import dev.alembiconsProject.alembicons.data.SecondarySourceKey
import dev.alembiconsProject.alembicons.data.SecondaryTextTypeKey
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TEXT_TYPE_DEFAULT
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getBackgroundColor
import dev.alembiconsProject.alembicons.data.getIconColor
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.getStringValue

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
    val monochrome: Boolean,
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
    // Which pack's fallback styling to give apps neither pack themes (NONE = leave them raw).
    val fallbackSource: FallbackSource = FallbackSource.NONE
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

            return GenerationOptions(
                primarySource = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT),
                primaryImageEdit = preferences.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT),
                primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT),
                primaryIconPack = preferences.getStringValue(PrimaryIconPackKey),
                secondarySource = preferences.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT),
                secondaryImageEdit = preferences.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT),
                secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT),
                secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey),
                color = iconColor.toArgb(),
                bgColor = bgColor.toArgb(),
                vector = preferences.getBooleanValue(IncludeVectorKey),
                monochrome = preferences.getBooleanValue(MonochromeKey),
                themed = preferences.getBooleanValue(ExportThemedKey),
                override = override,
                fallbackSource = preferences.getEnumValue(FallbackSourceKey, FALLBACK_SOURCE_DEFAULT)
            )
        }
    }
}