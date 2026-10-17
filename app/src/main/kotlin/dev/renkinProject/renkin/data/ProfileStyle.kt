package dev.renkinProject.renkin.data

import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape

/** Absence is a stored choice: resolve defaults only when projecting settings for execution. */
internal data class ProfileStyle(
    val source: ProfileSourceStyle = ProfileSourceStyle(),
    val modifiers: ProfileModifierStyle = ProfileModifierStyle(),
    val export: ProfileExportStyle = ProfileExportStyle(),
    val policy: ProfileOperationPolicy = ProfileOperationPolicy(),
    val builtPrimary: ProfileBuiltPrimary = ProfileBuiltPrimary()
)

internal data class ProfileSourceStyle(
    val primary: ProfileSourceSelection = ProfileSourceSelection(),
    val secondary: ProfileSourceSelection = ProfileSourceSelection(),
    val fallback: ProfileEnumValue<FallbackSource>? = null,
    val fontPath: String? = null,
    val applicationIcon: ProfileApplicationIconStyle = ProfileApplicationIconStyle()
)

internal data class ProfileApplicationIconStyle(
    val variant: ProfileEnumValue<ApplicationIconVariant>? = null,
    val fullIcon: Boolean? = null,
    val invertMonochrome: Boolean? = null,
    val materialYouScheme: Int? = null,
    val materialYouForeground: ProfileColorStyle = ProfileColorStyle(),
    val materialYouBackground: ProfileColorStyle = ProfileColorStyle()
)

internal data class ProfileSourceSelection(
    val source: ProfileEnumValue<Source>? = null,
    val textType: ProfileEnumValue<TextType>? = null,
    val iconPack: String? = null
)

internal data class ProfileModifierStyle(
    val generation: ProfileGenerationStyle = ProfileGenerationStyle(),
    val global: ProfileGlobalStyle = ProfileGlobalStyle()
)

internal data class ProfileGenerationStyle(
    val primaryEffect: ProfileEnumValue<ImageEdit>? = null,
    val secondaryEffect: ProfileEnumValue<ImageEdit>? = null,
    val color: ProfileColorStyle = ProfileColorStyle()
)

internal data class ProfileGlobalStyle(
    val shape: ProfileShapeStyle = ProfileShapeStyle(),
    val iconScalePercent: Int? = null,
    val colorize: ProfileColorizeStyle = ProfileColorizeStyle(),
    val outline: ProfileOutlineStyle = ProfileOutlineStyle()
)

internal data class ProfileShapeStyle(
    val shape: ProfileEnumValue<IconShape>? = null,
    val crop: Boolean? = null,
    val scalePercent: Int? = null,
    val fill: ProfileColorStyle = ProfileColorStyle()
)

internal data class ProfileColorizeStyle(
    val enabled: Boolean? = null,
    val flat: Boolean? = null,
    val lighten: Boolean? = null,
    val monochrome: Boolean? = null,
    val inverse: Boolean? = null,
    val color: ProfileColorStyle = ProfileColorStyle()
)

internal data class ProfileOutlineStyle(
    val add: Boolean? = null,
    val widthPx: Int? = null,
    val color: ProfileColorStyle = ProfileColorStyle()
)

// Keep configured colour strings and the legacy gradient stop separately; resolving them here
// would replace missing theme defaults or normalize disabled settings.
internal data class ProfileColorStyle(
    val firstColor: String? = null,
    val mode: ProfileEnumValue<ColorizerMode>? = null,
    val gradientType: ProfileEnumValue<GradientType>? = null,
    val gradientAngle: Int? = null,
    val gradientColors: String? = null,
    val gradientPositions: String? = null,
    val legacyGradientColor: String? = null
)

internal data class ProfileExportStyle(
    val includeVector: Boolean? = null,
    val monochrome: Boolean? = null,
    val themed: Boolean? = null,
    val background: ProfileColorStyle = ProfileColorStyle()
)

internal data class ProfileOperationPolicy(
    val overrideExisting: Boolean? = null,
    val calendarIcons: Boolean? = null,
    val applyGenerated: Boolean? = null,
    val applyExisting: Boolean? = null,
    val applyCustom: Boolean? = null,
    val includeEmpty: Boolean? = null
)

internal data class ProfileBuiltPrimary(
    val source: ProfileEnumValue<Source>? = null,
    val iconPack: String? = null
)

internal sealed interface ProfileEnumValue<E : Enum<E>> {
    data class Known<E : Enum<E>>(val value: E) : ProfileEnumValue<E>
    data class LegacyOrdinal<E : Enum<E>>(val ordinal: Int) : ProfileEnumValue<E>

    fun toLegacyOrdinal(): Int = when (this) {
        is Known -> value.ordinal
        is LegacyOrdinal -> ordinal
    }

    fun resolve(default: E): E = when (this) {
        is Known -> value
        is LegacyOrdinal -> default
    }

    companion object {
        fun <E : Enum<E>> fromLegacy(ordinal: Int, entries: List<E>): ProfileEnumValue<E> =
            entries.getOrNull(ordinal)?.let { Known(it) } ?: LegacyOrdinal<E>(ordinal)
    }
}
