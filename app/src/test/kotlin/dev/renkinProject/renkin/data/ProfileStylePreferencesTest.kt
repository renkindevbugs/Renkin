package dev.renkinProject.renkin.data

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfileStylePreferencesTest {
    @Test
    fun schemaClassifiesEveryRegisteredKeyExactlyOnceWithUniqueUnambiguousPaths() {
        val fields = ProfileStyleSchema.fields
        assertEquals(ProfilePrefKeys.toSet(), fields.map { it.key }.toSet())
        assertEquals(ProfilePrefKeys.size, fields.size)
        assertEquals(fields.size, fields.map { it.path }.toSet().size)
        assertEquals(ProfileStyleSchema.groups, fields.map { it.path.substringBefore('.') }.toSet())
        for (field in fields) {
            assertTrue(field.path.split('.').all { it.isNotEmpty() })
            assertFalse(fields.any { it.path.startsWith(field.path + ".") })
        }
    }

    @Test
    fun eachKeyPreservesPresenceAndBothBooleanValuesIndependently() {
        for ((index, key) in ProfilePrefKeys.withIndex()) {
            for (boolean in listOf(false, true)) {
                val value = profileTestValue(key, index, boolean)
                val source = mutablePreferencesOf().apply {
                    replaceProfilePrefs(JSONObject().put(key.name, value).toString())
                }
                val style = ProfileStylePreferences.capture(source)
                assertEquals(key.name, source.asMap(), ProfileStylePreferences.toPreferences(style).asMap())
                assertEquals(key.name, style, ProfileStyleCodec.decode(ProfileStyleCodec.encode(style)))
            }
        }
    }

    @Test
    fun populatedProfileKeepsDistinctValuesAndByteIdenticalLegacyEncoding() {
        val expected = JSONObject().apply {
            ProfilePrefKeys.forEachIndexed { index, key -> put(key.name, profileTestValue(key, index, index % 2 == 0)) }
        }
        val source = mutablePreferencesOf(DarkModeKey to DarkMode.DARK.ordinal, ActiveProfileIdKey to 9L)
        source.replaceProfilePrefs(expected.toString())
        val captured = ProfileStylePreferences.capture(source)
        assertEquals(source.asMap().filterKeys { it in ProfilePrefKeys }, ProfileStylePreferences.toPreferences(captured).asMap())
        assertEquals(expected.toString(), source.snapshotProfilePrefs())
        assertEquals(captured, ProfileStyleCodec.decode(ProfileStyleCodec.encode(captured)))
        source.clear()
        assertEquals(expected.toString(), ProfileStylePreferences.toPreferences(captured).snapshotProfilePrefs())
    }

    @Test
    fun missingDefaultsAndDisabledConfigurationRemainStoredChoices() {
        val source = preferencesOf(
            GlobalColorizeKey to false, OutlineAddKey to false, GlobalShapeKey to IconShape.NONE.ordinal,
            GlobalColorizeColorKey to "invalid configured colour", GlobalColorizerGradientPositionsKey to "0.9,-2,0.1",
            GlobalShapeScaleKey to -7, GlobalIconScaleKey to 1500, OutlineWidthKey to -4,
            TextFontKey to "/missing/font.ttf", PrimaryIconPackKey to ""
        )
        val style = ProfileStylePreferences.capture(source)
        assertNull(style.modifiers.generation.color.firstColor)
        assertNull(style.export.background.firstColor)
        assertNull(style.source.primary.source)
        assertEquals(false, style.modifiers.global.colorize.enabled)
        assertEquals(source.asMap(), ProfileStylePreferences.toPreferences(style).asMap())
        assertEquals(ProfileStyle(), ProfileStylePreferences.capture(mutablePreferencesOf()))
    }

    @Test
    fun allKnownEnumValuesAndUnknownOrdinalsSurviveEachEnumKey() {
        val enumKeys = listOf(
            Pair(PrimarySourceKey, Source.entries.size), Pair(SecondarySourceKey, Source.entries.size),
            Pair(BuiltPrimarySourceKey, Source.entries.size), Pair(PrimaryTextTypeKey, TextType.entries.size),
            Pair(SecondaryTextTypeKey, TextType.entries.size), Pair(FallbackSourceKey, FallbackSource.entries.size),
            Pair(PrimaryImageEditKey, ImageEdit.entries.size), Pair(SecondaryImageEditKey, ImageEdit.entries.size),
            Pair(GlobalShapeKey, IconShape.entries.size),
            Pair(ColorizerModeKey, ColorizerMode.entries.size), Pair(BackgroundColorizerModeKey, ColorizerMode.entries.size),
            Pair(GlobalShapeColorizerModeKey, ColorizerMode.entries.size), Pair(OutlineColorizerModeKey, ColorizerMode.entries.size),
            Pair(GlobalColorizerModeKey, ColorizerMode.entries.size),
            Pair(ColorizerGradientTypeKey, GradientType.entries.size), Pair(BackgroundGradientTypeKey, GradientType.entries.size),
            Pair(GlobalShapeGradientTypeKey, GradientType.entries.size), Pair(OutlineGradientTypeKey, GradientType.entries.size),
            Pair(GlobalColorizerGradientTypeKey, GradientType.entries.size)
        )
        for ((key, size) in enumKeys) {
            for (ordinal in (0 until size).toList() + listOf(-1, size, Int.MIN_VALUE, Int.MAX_VALUE)) {
                val source = preferencesOf(key to ordinal)
                val captured = ProfileStylePreferences.capture(source)
                val decoded = ProfileStyleCodec.decode(ProfileStyleCodec.encode(captured))
                assertEquals("${key.name}: $ordinal", source.asMap(), ProfileStylePreferences.toPreferences(decoded).asMap())
            }
        }
        for (source in Source.entries) {
            assertEquals(source, ProfileEnumValue.fromLegacy(source.ordinal, Source.entries).resolve(Source.NONE))
        }
        assertEquals(Source.ICON_PACK, ProfileEnumValue.fromLegacy(-1, Source.entries).resolve(Source.ICON_PACK))
    }
}

private fun profileTestValue(key: Preferences.Key<*>, index: Int, boolean: Boolean): Any =
    listOf<Any>(boolean, -1000 - index, "${key.name}:$index \"raw\"\n\uD83C\uDF08")
        .single { isValidPersistedPreference(key.name, it) }
