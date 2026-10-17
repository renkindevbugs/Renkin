package dev.renkinProject.renkin.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import org.json.JSONObject

internal object ProfileStyleSchema {
    val groups = setOf("source", "modifiers", "export", "policy", "builtPrimary")

    val fields: List<ProfileStyleField> = buildList {
        add(EnumField(PrimarySourceKey, "source.primary.kind", Source.entries) { it.source.primary.source })
        add(EnumField(PrimaryTextTypeKey, "source.primary.textType", TextType.entries) { it.source.primary.textType })
        add(StringField(PrimaryIconPackKey, "source.primary.iconPack") { it.source.primary.iconPack })
        add(EnumField(SecondarySourceKey, "source.secondary.kind", Source.entries) { it.source.secondary.source })
        add(EnumField(SecondaryTextTypeKey, "source.secondary.textType", TextType.entries) { it.source.secondary.textType })
        add(StringField(SecondaryIconPackKey, "source.secondary.iconPack") { it.source.secondary.iconPack })
        add(EnumField(FallbackSourceKey, "source.fallback", FallbackSource.entries) { it.source.fallback })
        add(StringField(TextFontKey, "source.fontPath") { it.source.fontPath })
        add(EnumField(AppIconVariantKey, "source.applicationIcon.variant", ApplicationIconVariant.entries) { it.source.applicationIcon.variant })
        add(BooleanField(AppIconFullIconKey, "source.applicationIcon.fullIcon") { it.source.applicationIcon.fullIcon })
        add(BooleanField(AppIconInvertMonochromeKey, "source.applicationIcon.invertMonochrome") { it.source.applicationIcon.invertMonochrome })
        add(IntField(AppIconMaterialYouSchemeKey, "source.applicationIcon.materialYouScheme") { it.source.applicationIcon.materialYouScheme })
        addAll(colorFields(AppIconMaterialYouForegroundStyleKeys, "source.applicationIcon.materialYouForeground") { it.source.applicationIcon.materialYouForeground })
        addAll(colorFields(AppIconMaterialYouBackgroundStyleKeys, "source.applicationIcon.materialYouBackground") { it.source.applicationIcon.materialYouBackground })
        add(EnumField(PrimaryImageEditKey, "modifiers.generation.primaryEffect", ImageEdit.entries) { it.modifiers.generation.primaryEffect })
        add(EnumField(SecondaryImageEditKey, "modifiers.generation.secondaryEffect", ImageEdit.entries) { it.modifiers.generation.secondaryEffect })
        addAll(colorFields(ColorizerStyleKeys, "modifiers.generation.color") { it.modifiers.generation.color })
        add(EnumField(GlobalShapeKey, "modifiers.global.shape.kind", IconShape.entries) { it.modifiers.global.shape.shape })
        add(BooleanField(GlobalShapeCropKey, "modifiers.global.shape.crop") { it.modifiers.global.shape.crop })
        add(IntField(GlobalShapeScaleKey, "modifiers.global.shape.scalePercent") { it.modifiers.global.shape.scalePercent })
        addAll(colorFields(GlobalShapeStyleKeys, "modifiers.global.shape.fill") { it.modifiers.global.shape.fill })
        add(IntField(GlobalIconScaleKey, "modifiers.global.iconScalePercent") { it.modifiers.global.iconScalePercent })
        add(BooleanField(GlobalColorizeKey, "modifiers.global.colorize.enabled") { it.modifiers.global.colorize.enabled })
        add(BooleanField(GlobalColorizeFlatKey, "modifiers.global.colorize.flat") { it.modifiers.global.colorize.flat })
        add(BooleanField(GlobalColorizeLightenKey, "modifiers.global.colorize.lighten") { it.modifiers.global.colorize.lighten })
        add(BooleanField(GlobalColorizeMonochromeKey, "modifiers.global.colorize.monochrome") { it.modifiers.global.colorize.monochrome })
        add(BooleanField(GlobalColorizeInverseKey, "modifiers.global.colorize.inverse") { it.modifiers.global.colorize.inverse })
        addAll(colorFields(GlobalColorizerStyleKeys, "modifiers.global.colorize.color") { it.modifiers.global.colorize.color })
        add(BooleanField(OutlineAddKey, "modifiers.global.outline.add") { it.modifiers.global.outline.add })
        add(IntField(OutlineWidthKey, "modifiers.global.outline.widthPx") { it.modifiers.global.outline.widthPx })
        addAll(colorFields(OutlineStyleKeys, "modifiers.global.outline.color") { it.modifiers.global.outline.color })
        add(BooleanField(IncludeVectorKey, "export.includeVector") { it.export.includeVector })
        add(BooleanField(MonochromeKey, "export.monochrome") { it.export.monochrome })
        add(BooleanField(ExportThemedKey, "export.themed") { it.export.themed })
        addAll(colorFields(BackgroundStyleKeys, "export.background") { it.export.background })
        add(BooleanField(OverrideIconKey, "policy.overrideExisting") { it.policy.overrideExisting })
        add(BooleanField(CalendarIconsKey, "policy.calendarIcons") { it.policy.calendarIcons })
        add(BooleanField(GlobalApplyGeneratedKey, "policy.applyGenerated") { it.policy.applyGenerated })
        add(BooleanField(GlobalApplyExistingKey, "policy.applyExisting") { it.policy.applyExisting })
        add(BooleanField(GlobalApplyCustomKey, "policy.applyCustom") { it.policy.applyCustom })
        add(BooleanField(GlobalIncludeEmptyKey, "policy.includeEmpty") { it.policy.includeEmpty })
        add(EnumField(BuiltPrimarySourceKey, "builtPrimary.kind", Source.entries) { it.builtPrimary.source })
        add(StringField(BuiltPrimaryIconPackKey, "builtPrimary.iconPack") { it.builtPrimary.iconPack })
    }

    private fun colorFields(
        keys: ColorStyleKeys,
        path: String,
        color: (ProfileStyle) -> ProfileColorStyle
    ): List<ProfileStyleField> = buildList {
        add(StringField(keys.firstColor, "$path.firstColor") { color(it).firstColor })
        add(EnumField(keys.mode, "$path.mode", ColorizerMode.entries) { color(it).mode })
        add(EnumField(keys.gradientType, "$path.gradientType", GradientType.entries) { color(it).gradientType })
        add(IntField(keys.gradientAngle, "$path.gradientAngle") { color(it).gradientAngle })
        add(StringField(keys.gradientColors, "$path.gradientColors") { color(it).gradientColors })
        add(StringField(keys.gradientPositions, "$path.gradientPositions") { color(it).gradientPositions })
        keys.legacyGradientColor?.let { key ->
            add(StringField(key, "$path.legacyGradientColor") { color(it).legacyGradientColor })
        }
    }
}

internal sealed class ProfileStyleField(val key: Preferences.Key<*>, val path: String) {
    abstract fun writeLegacy(style: ProfileStyle, target: MutablePreferences)
    abstract fun jsonValue(style: ProfileStyle): Any?
    abstract fun readJson(reader: JsonReader, target: MutablePreferences)
}

private class BooleanField(
    private val typedKey: Preferences.Key<Boolean>, path: String,
    private val value: (ProfileStyle) -> Boolean?
) : ProfileStyleField(typedKey, path) {
    override fun writeLegacy(style: ProfileStyle, target: MutablePreferences) {
        value(style)?.let { target[typedKey] = it }
    }
    override fun jsonValue(style: ProfileStyle): Any? = value(style)
    override fun readJson(reader: JsonReader, target: MutablePreferences) {
        require(reader.peek() == JsonToken.BOOLEAN) { "Expected Boolean at $path" }
        target[typedKey] = reader.nextBoolean()
    }
}

private class IntField(
    private val typedKey: Preferences.Key<Int>, path: String,
    private val value: (ProfileStyle) -> Int?
) : ProfileStyleField(typedKey, path) {
    override fun writeLegacy(style: ProfileStyle, target: MutablePreferences) {
        value(style)?.let { target[typedKey] = it }
    }
    override fun jsonValue(style: ProfileStyle): Any? = value(style)
    override fun readJson(reader: JsonReader, target: MutablePreferences) {
        target[typedKey] = reader.profileInt(path)
    }
}

private class StringField(
    private val typedKey: Preferences.Key<String>, path: String,
    private val value: (ProfileStyle) -> String?
) : ProfileStyleField(typedKey, path) {
    override fun writeLegacy(style: ProfileStyle, target: MutablePreferences) {
        value(style)?.let { target[typedKey] = it }
    }
    override fun jsonValue(style: ProfileStyle): Any? = value(style)
    override fun readJson(reader: JsonReader, target: MutablePreferences) {
        require(reader.peek() == JsonToken.STRING) { "Expected String at $path" }
        target[typedKey] = reader.nextString().also { it.requireProfileUnicode() }
    }
}

private class EnumField<E : Enum<E>>(
    private val typedKey: Preferences.Key<Int>, path: String,
    private val entries: List<E>,
    private val value: (ProfileStyle) -> ProfileEnumValue<E>?
) : ProfileStyleField(typedKey, path) {
    override fun writeLegacy(style: ProfileStyle, target: MutablePreferences) {
        value(style)?.let {
            require(it !is ProfileEnumValue.LegacyOrdinal || entries.getOrNull(it.ordinal) == null) {
                "Known ordinal must use an enum name at $path"
            }
            target[typedKey] = it.toLegacyOrdinal()
        }
    }
    override fun jsonValue(style: ProfileStyle): Any? = when (val stored = value(style)) {
        null -> null
        is ProfileEnumValue.Known -> stored.value.name
        is ProfileEnumValue.LegacyOrdinal -> {
            require(entries.getOrNull(stored.ordinal) == null) { "Known ordinal must use an enum name at $path" }
            JSONObject().put("legacyOrdinal", stored.ordinal)
        }
    }
    override fun readJson(reader: JsonReader, target: MutablePreferences) {
        val ordinal = when (reader.peek()) {
            JsonToken.STRING -> {
                val name = reader.nextString()
                requireNotNull(entries.find { it.name == name }) { "Unknown enum at $path" }.ordinal
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                require(reader.hasNext() && reader.nextName() == "legacyOrdinal") { "Invalid legacy enum at $path" }
                val raw = reader.profileInt(path)
                require(entries.getOrNull(raw) == null && !reader.hasNext()) { "Invalid legacy ordinal at $path" }
                reader.endObject()
                raw
            }
            else -> throw IllegalArgumentException("Expected enum at $path")
        }
        target[typedKey] = ordinal
    }
}

internal fun JsonReader.profileInt(path: String): Int {
    require(peek() == JsonToken.NUMBER) { "Expected integer at $path" }
    return requireNotNull(nextString().toIntOrNull()) { "Invalid integer at $path" }
}
