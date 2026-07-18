package dev.renkinProject.renkin.data

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataPreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun darkMode_followSystem_returnsSystemValue() {
        assertEquals(true, isDarkModeEnabled(DarkMode.FOLLOW_SYSTEM, system = true))
        assertEquals(false, isDarkModeEnabled(DarkMode.FOLLOW_SYSTEM, system = false))
    }

    @Test
    fun darkMode_explicit_ignoresSystemValue() {
        assertEquals(true, isDarkModeEnabled(DarkMode.DARK, system = false))
        assertEquals(false, isDarkModeEnabled(DarkMode.LIGHT, system = true))
    }

    @Test
    fun getEnumValue_readsStoredOrdinal() {
        val prefs = preferencesOf(DarkModeKey to DarkMode.DARK.ordinal)
        assertEquals(DarkMode.DARK, prefs.getEnumValue(DarkModeKey, DarkMode.FOLLOW_SYSTEM))
    }

    @Test
    fun getEnumValue_missingKey_returnsDefault() {
        assertEquals(DarkMode.LIGHT, emptyPreferences().getEnumValue(DarkModeKey, DarkMode.LIGHT))
    }

    @Test
    fun getIntValue_readsStoredValue_andFallsBackToDefault() {
        val prefs = preferencesOf(AppSortOrderKey to 1)
        assertEquals(1, prefs.getIntValue(AppSortOrderKey, default = 0))
        assertEquals(5, emptyPreferences().getIntValue(AppSortOrderKey, default = 5))
    }

    @Test
    fun getBooleanValue_readsStoredValue_andFallsBackToDefault() {
        val prefs = preferencesOf(ExportThemedKey to true)
        assertEquals(true, prefs.getBooleanValue(ExportThemedKey))
        assertEquals(false, emptyPreferences().getBooleanValue(ExportThemedKey))
    }

    @Test
    fun getColorValue_invalidStoredValue_returnsDefault() {
        val fallback = Color(0xFF123456)
        val prefs = preferencesOf(IconColorKey to "not-a-color")

        assertEquals(fallback, prefs.getColorValue(IconColorKey, fallback))
    }

    @Test
    fun replaceProfilePrefs_wrongTypes_removePreviousProfileValues() {
        val prefs = mutablePreferencesOf(
            IncludeVectorKey to true,
            PrimarySourceKey to Source.ICON_PACK.ordinal,
            TextFontKey to "/old/font.ttf"
        )

        prefs.replaceProfilePrefs(
            """{"INCLUDE_VECTOR":"true","PRIMARY_SOURCE":2147483648,"TEXT_FONT":null}"""
        )

        assertNull(prefs[IncludeVectorKey])
        assertNull(prefs[PrimarySourceKey])
        assertNull(prefs[TextFontKey])
    }

    @Test
    fun replaceProfilePrefs_validValuesReplaceAndMissingValuesClear() {
        val prefs = mutablePreferencesOf(
            IncludeVectorKey to false,
            SecondaryIconPackKey to "old.pack"
        )

        prefs.replaceProfilePrefs(
            """{"INCLUDE_VECTOR":true,"PRIMARY_SOURCE":2,"TEXT_FONT":"/system/fonts/test.ttf"}"""
        )

        assertTrue(prefs[IncludeVectorKey] == true)
        assertEquals(2, prefs[PrimarySourceKey])
        assertEquals("/system/fonts/test.ttf", prefs[TextFontKey])
        assertNull(prefs[SecondaryIconPackKey])
        assertFalse(prefs.contains(SecondaryIconPackKey))
    }

    @Test
    fun replaceProfilePrefs_malformedJsonClearsAllProfileValues() {
        val prefs = mutablePreferencesOf(
            IncludeVectorKey to true,
            PrimaryIconPackKey to "some.pack",
            OutlineWidthKey to 8
        )

        prefs.replaceProfilePrefs("not-json")

        assertTrue(ProfilePrefKeys.none { prefs.contains(it) })
    }

    @Test
    fun persistedNumericSettings_areClampedToSafeRanges() {
        assertEquals(WATCH_CHECK_INTERVAL_DEFAULT, normalizeWatchCheckInterval(-1))
        assertEquals(WATCH_CHECK_INTERVAL_MIN, normalizeWatchCheckInterval(WATCH_CHECK_INTERVAL_MIN))
        assertEquals(OUTLINE_WIDTH_MIN, normalizeOutlineWidth(Int.MIN_VALUE))
        assertEquals(OUTLINE_WIDTH_MAX, normalizeOutlineWidth(Int.MAX_VALUE))
    }

    @Test
    fun consistentSnapshot_readsLatestPreferenceWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.root.resolve("settings.preferences_pb")
        }
        try {
            store.setIntValue(OutlineWidthKey, 16)

            assertEquals(16, store.getPreferencesAfterPendingWrites()[OutlineWidthKey])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistGlobalModifierPrefs_updatesOnlyStagedGlobalKeys() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.root.resolve("global-settings.preferences_pb")
        }
        try {
            store.setStringValue(PrimaryIconPackKey, "keep.pack")
            val staged = preferencesOf(
                GlobalIconScaleKey to 80,
                OutlineAddKey to true,
                GlobalColorizeMonochromeKey to true,
                GlobalColorizeInverseKey to true,
                GlobalApplyGeneratedKey to false,
                GlobalApplyExistingKey to true,
                GlobalApplyCustomKey to true
            )

            store.persistGlobalModifierPrefs(staged)
            val saved = store.getPreferencesAfterPendingWrites()

            assertEquals("keep.pack", saved[PrimaryIconPackKey])
            assertEquals(80, saved[GlobalIconScaleKey])
            assertTrue(saved[OutlineAddKey] == true)
            assertTrue(saved[GlobalColorizeMonochromeKey] == true)
            assertTrue(saved[GlobalColorizeInverseKey] == true)
            assertFalse(saved[GlobalApplyGeneratedKey] == true)
            assertTrue(saved[GlobalApplyExistingKey] == true)
            assertTrue(saved[GlobalApplyCustomKey] == true)
        } finally {
            scope.cancel()
        }
    }
}
