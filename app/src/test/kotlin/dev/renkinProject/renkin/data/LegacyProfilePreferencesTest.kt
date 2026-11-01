package dev.renkinProject.renkin.data

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LegacyProfilePreferencesTest {
    private fun registeredValues(): JSONObject = JSONObject().apply {
        for (key in ProfilePrefKeys) {
            val candidates = listOf<Any>(false, -137, "stored \"value\"\n\uD83C\uDF08")
                .filter { isValidPersistedPreference(key.name, it) }
            assertEquals("Each registered profile key has one supported type: ${key.name}", 1, candidates.size)
            put(key.name, candidates.single())
        }
    }

    @Test
    fun everyRegisteredKeyRoundTripsWithoutNormalizingStoredValuesOrIncludingApplicationSettings() {
        val expected = registeredValues()
        val source = mutablePreferencesOf(DarkModeKey to DarkMode.DARK.ordinal, ActiveProfileIdKey to 9L)
        source.replaceProfilePrefs(expected.toString())
        val encoded = JSONObject(source.snapshotProfilePrefs())
        assertEquals(ProfilePrefKeys.size, encoded.length())
        assertEquals(ProfilePrefKeys.map { it.name }.toSet(), encoded.keys().asSequence().toSet())
        for (key in ProfilePrefKeys) assertEquals(key.name, expected.get(key.name), encoded.get(key.name))
        assertFalse(encoded.has(DarkModeKey.name))
        assertFalse(encoded.has(ActiveProfileIdKey.name))
        val restored = mutablePreferencesOf()
        restored.replaceProfilePrefs(encoded.toString())
        assertEquals(source.asMap().filterKeys { it in ProfilePrefKeys }, restored.asMap())
    }

    @Test
    fun malformedAndWronglyTypedSnapshotsClearAllProfileValuesButPreserveApplicationSettings() {
        val existing = registeredValues()
        val wrongTypes = JSONObject().apply {
            for (key in ProfilePrefKeys) {
                put(key.name, when (existing.get(key.name)) {
                    is Boolean -> "false"
                    is Int -> 1.5
                    else -> true
                })
            }
        }
        for (snapshot in listOf("", "not-json", "[]", "{}", wrongTypes.toString())) {
            val target = mutablePreferencesOf(
                DarkModeKey to DarkMode.DARK.ordinal, ActiveProfileIdKey to 9L,
                AutoBackupTreeUriKey to "content://backups", OnboardingSeenKey to true
            )
            target.replaceProfilePrefs(existing.toString())
            target.replaceProfilePrefs(snapshot)
            assertTrue("Profile values leaked after $snapshot", ProfilePrefKeys.none { target.contains(it) })
            assertEquals(DarkMode.DARK.ordinal, target[DarkModeKey])
            assertEquals(9L, target[ActiveProfileIdKey])
            assertEquals("content://backups", target[AutoBackupTreeUriKey])
            assertEquals(true, target[OnboardingSeenKey])
        }
    }

    @Test
    fun presenceAndDisabledSettingsSurviveWhileMissingColoursAndUnknownFieldsRemainAbsent() {
        val source = preferencesOf(
            GlobalColorizeKey to false, GlobalShapeCropKey to true,
            GlobalShapeGradientColorsKey to "#FF010203,#FF040506",
            IncludeVectorKey to false, PrimaryIconPackKey to "", TextFontKey to "/missing/font.ttf"
        )
        val snapshot = JSONObject(source.snapshotProfilePrefs())
            .put("future.profile.setting", 7).put(DarkModeKey.name, DarkMode.LIGHT.ordinal)
        val target = mutablePreferencesOf(
            IconColorKey to "#FFFFFFFF", BackgroundColorKey to "#FF000000", DarkModeKey to DarkMode.DARK.ordinal
        )
        target.replaceProfilePrefs(snapshot.toString())
        assertEquals(source.asMap(), target.asMap().filterKeys { it in ProfilePrefKeys })
        assertEquals(DarkMode.DARK.ordinal, target[DarkModeKey])
        assertNull(target[IconColorKey])
        assertNull(target[BackgroundColorKey])
        assertFalse(JSONObject(target.snapshotProfilePrefs()).has("future.profile.setting"))
        assertEquals("{}", emptyPreferences().snapshotProfilePrefs())
    }

    @Test
    fun integerBoundariesAreAcceptedWithoutCoercingFractionsStringsOrOverflow() {
        for (value in listOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0)) {
            val target = mutablePreferencesOf()
            target.replaceProfilePrefs(JSONObject().put(PrimarySourceKey.name, value.toLong()).toString())
            assertEquals(value, target[PrimarySourceKey])
        }
        for (value in listOf<Any>(Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1, 1.5, "2", JSONObject.NULL)) {
            val target = mutablePreferencesOf(PrimarySourceKey to 2)
            target.replaceProfilePrefs(JSONObject().put(PrimarySourceKey.name, value).toString())
            assertNull(target[PrimarySourceKey])
        }
    }

    @Test
    fun builtPrimaryPatchPreservesUnknownStructuredFieldsAndOtherStoredSettings() {
        val original = registeredValues()
            .put("future.object", JSONObject().put("enabled", false).put("items", JSONArray().put(1).put("two")))
            .put("future.null", JSONObject.NULL)
        val patched = JSONObject(original.toString().withBuiltPrimaryPrefs(preferencesOf(
            PrimarySourceKey to Source.ICON_PACK.ordinal, PrimaryIconPackKey to "built.pack"
        )))
        assertEquals(original.keys().asSequence().toSet(), patched.keys().asSequence().toSet())
        for (name in original.keys()) {
            if (name != BuiltPrimarySourceKey.name && name != BuiltPrimaryIconPackKey.name) {
                assertEquals(name, original.get(name).toString(), patched.get(name).toString())
            }
        }
        assertEquals(Source.ICON_PACK.ordinal, patched.getInt(BuiltPrimarySourceKey.name))
        assertEquals("built.pack", patched.getString(BuiltPrimaryIconPackKey.name))
        assertTrue(patched.isNull("future.null"))
    }

    @Test
    fun builtPrimaryPatchOfMalformedSnapshotUsesOnlyBuiltDefaults() {
        for (snapshot in listOf("", "not-json", "[]", "{}")) {
            val patched = JSONObject(snapshot.withBuiltPrimaryPrefs(emptyPreferences()))
            assertEquals(setOf(BuiltPrimarySourceKey.name, BuiltPrimaryIconPackKey.name), patched.keys().asSequence().toSet())
            assertEquals(SOURCE_DEFAULT.ordinal, patched.getInt(BuiltPrimarySourceKey.name))
            assertEquals("", patched.getString(BuiltPrimaryIconPackKey.name))
        }
    }
}
