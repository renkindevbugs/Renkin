package dev.renkinProject.renkin.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape

internal object ProfileStylePreferences {
    fun capture(preferences: Preferences): ProfileStyle = with(preferences) {
        ProfileStyle(
            source = ProfileSourceStyle(
                primary = ProfileSourceSelection(
                    storedEnum(PrimarySourceKey, Source.entries), storedEnum(PrimaryTextTypeKey, TextType.entries), this[PrimaryIconPackKey]
                ),
                secondary = ProfileSourceSelection(
                    storedEnum(SecondarySourceKey, Source.entries), storedEnum(SecondaryTextTypeKey, TextType.entries), this[SecondaryIconPackKey]
                ),
                fallback = storedEnum(FallbackSourceKey, FallbackSource.entries),
                fontPath = this[TextFontKey]
            ),
            modifiers = ProfileModifierStyle(
                generation = ProfileGenerationStyle(
                    primaryEffect = storedEnum(PrimaryImageEditKey, ImageEdit.entries),
                    secondaryEffect = storedEnum(SecondaryImageEditKey, ImageEdit.entries),
                    color = color(ColorizerStyleKeys)
                ),
                global = ProfileGlobalStyle(
                    shape = ProfileShapeStyle(
                        shape = storedEnum(GlobalShapeKey, IconShape.entries), crop = this[GlobalShapeCropKey],
                        scalePercent = this[GlobalShapeScaleKey], fill = color(GlobalShapeStyleKeys)
                    ),
                    iconScalePercent = this[GlobalIconScaleKey],
                    colorize = ProfileColorizeStyle(
                        enabled = this[GlobalColorizeKey], flat = this[GlobalColorizeFlatKey],
                        lighten = this[GlobalColorizeLightenKey], monochrome = this[GlobalColorizeMonochromeKey],
                        inverse = this[GlobalColorizeInverseKey], color = color(GlobalColorizerStyleKeys)
                    ),
                    outline = ProfileOutlineStyle(
                        add = this[OutlineAddKey], widthPx = this[OutlineWidthKey], color = color(OutlineStyleKeys)
                    )
                )
            ),
            export = ProfileExportStyle(
                includeVector = this[IncludeVectorKey], monochrome = this[MonochromeKey],
                themed = this[ExportThemedKey], background = color(BackgroundStyleKeys)
            ),
            policy = ProfileOperationPolicy(
                overrideExisting = this[OverrideIconKey], calendarIcons = this[CalendarIconsKey],
                applyGenerated = this[GlobalApplyGeneratedKey], applyExisting = this[GlobalApplyExistingKey],
                applyCustom = this[GlobalApplyCustomKey], includeEmpty = this[GlobalIncludeEmptyKey]
            ),
            builtPrimary = ProfileBuiltPrimary(storedEnum(BuiltPrimarySourceKey, Source.entries), this[BuiltPrimaryIconPackKey])
        )
    }

    fun toPreferences(style: ProfileStyle): Preferences = mutablePreferencesOf().apply {
        ProfileStyleSchema.fields.forEach { it.writeLegacy(style, this) }
    }.toPreferences()

    private fun Preferences.color(keys: ColorStyleKeys) = ProfileColorStyle(
        firstColor = this[keys.firstColor], mode = storedEnum(keys.mode, ColorizerMode.entries),
        gradientType = storedEnum(keys.gradientType, GradientType.entries), gradientAngle = this[keys.gradientAngle],
        gradientColors = this[keys.gradientColors], gradientPositions = this[keys.gradientPositions],
        legacyGradientColor = keys.legacyGradientColor?.let { this[it] }
    )

    private fun <E : Enum<E>> Preferences.storedEnum(key: Preferences.Key<Int>, entries: List<E>): ProfileEnumValue<E>? =
        this[key]?.let { ProfileEnumValue.fromLegacy(it, entries) }
}
