package dev.renkinProject.renkin.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import org.json.JSONObject
import java.io.StringReader

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
                fontPath = this[TextFontKey],
                applicationIcon = ProfileApplicationIconStyle(
                    variant = storedEnum(AppIconVariantKey, ApplicationIconVariant.entries),
                    fullIcon = this[AppIconFullIconKey], invertMonochrome = this[AppIconInvertMonochromeKey],
                    materialYouScheme = this[AppIconMaterialYouSchemeKey],
                    materialYouForeground = color(AppIconMaterialYouForegroundStyleKeys),
                    materialYouBackground = color(AppIconMaterialYouBackgroundStyleKeys)
                )
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
                    ),
                    shadow = ProfileShadowStyle(
                        enabled = this[GlobalShadowKey], blurPx = this[GlobalShadowBlurKey],
                        distancePx = this[GlobalShadowDistanceKey], angleDegrees = this[GlobalShadowAngleKey],
                        allDirections = this[GlobalShadowAllDirectionsKey], opacityPercent = this[GlobalShadowOpacityKey],
                        color = color(GlobalShadowStyleKeys)
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

    /** Strict compatibility copy reader for explicit repair and typed portable archives. */
    fun decodeLegacySnapshot(snapshot: String): ProfileStyle {
        // Flat legacy key names can be longer than the nested typed document's field names.
        val fieldOverhead = ProfilePrefKeys.sumOf { it.name.length + 16 } + 2
        require(snapshot.length <= ProfileStyleCodec.MAX_DOCUMENT_CHARS + fieldOverhead) { "Legacy style is too large" }
        snapshot.requireProfileUnicode()
        require(snapshot.toByteArray(Charsets.UTF_8).size <= ProfileStyleCodec.MAX_DOCUMENT_BYTES + fieldOverhead) { "Legacy style is too large" }
        val values = JSONObject()
        val keys = ProfilePrefKeys.map { it.name }.toSet()
        JsonReader(StringReader(snapshot)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val seen = mutableSetOf<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(seen.add(name)) { "Duplicate legacy style field" }
                require(name in keys) { "Unknown legacy style field" }
                val value: Any = when (reader.peek()) {
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NUMBER -> reader.profileInt(name)
                    JsonToken.STRING -> reader.nextString()
                    else -> throw IllegalArgumentException("Invalid legacy style value")
                }
                require(isValidPersistedPreference(name, value)) { "Invalid legacy style type" }
                values.put(name, value)
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing legacy style content" }
        }
        return capture(mutablePreferencesOf().apply { replaceProfilePrefs(values.toString()) })
    }

    private fun Preferences.color(keys: ColorStyleKeys) = ProfileColorStyle(
        firstColor = this[keys.firstColor], mode = storedEnum(keys.mode, ColorizerMode.entries),
        gradientType = storedEnum(keys.gradientType, GradientType.entries), gradientAngle = this[keys.gradientAngle],
        gradientColors = this[keys.gradientColors], gradientPositions = this[keys.gradientPositions],
        legacyGradientColor = keys.legacyGradientColor?.let { this[it] }
    )

    private fun <E : Enum<E>> Preferences.storedEnum(key: Preferences.Key<Int>, entries: List<E>): ProfileEnumValue<E>? =
        this[key]?.let { ProfileEnumValue.fromLegacy(it, entries) }
}
