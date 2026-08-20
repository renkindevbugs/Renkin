package dev.renkinProject.renkin.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    fun appSortOrder_persistedOrdinalsStayStable() {
        assertEquals(0, AppSortOrder.NAME.ordinal)
        assertEquals(1, AppSortOrder.INSTALL_DATE.ordinal)
        val prefs = preferencesOf(AppSortOrderKey to AppSortOrder.INSTALL_DATE.ordinal)
        assertEquals(
            AppSortOrder.INSTALL_DATE,
            prefs.getEnumValue(AppSortOrderKey, AppSortOrder.NAME)
        )
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
    fun colorizerGradientSettingsRoundTripThroughProfileSnapshot() {
        val source = preferencesOf(
            ColorizerModeKey to 1,
            ColorizerGradientColorKey to "#FF123456",
            ColorizerGradientColorsKey to "#FF123456,#FFABCDEF",
            ColorizerGradientAngleKey to 248,
            ColorizerGradientTypeKey to 1,
            GlobalColorizerModeKey to 1,
            GlobalColorizerGradientColorKey to "#FF654321",
            GlobalColorizerGradientAngleKey to 90,
            GlobalColorizerGradientTypeKey to 0
        )
        val restored = mutablePreferencesOf()

        restored.replaceProfilePrefs(source.snapshotProfilePrefs())

        assertEquals(1, restored[ColorizerModeKey])
        assertEquals("#FF123456", restored[ColorizerGradientColorKey])
        assertEquals("#FF123456,#FFABCDEF", restored[ColorizerGradientColorsKey])
        assertEquals(248, restored[ColorizerGradientAngleKey])
        assertEquals(1, restored[ColorizerGradientTypeKey])
        assertEquals(1, restored[GlobalColorizerModeKey])
        assertEquals("#FF654321", restored[GlobalColorizerGradientColorKey])
        assertEquals(90, restored[GlobalColorizerGradientAngleKey])
        assertEquals(0, restored[GlobalColorizerGradientTypeKey])
    }

    /** A throwaway DataStore in the test's temp folder, torn down with its scope. */
    private suspend fun withStore(
        fileName: String = "style.preferences_pb",
        block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.root.resolve(fileName)
        }
        try {
            block(store)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun setPrimarySource_writesTheSourceAndItsPackTogether() = runBlocking {
        withStore("primary-source.preferences_pb") { store ->
            store.setPrimarySource(Source.ICON_PACK, "com.example.pack")
            val saved = store.getPreferencesAfterPendingWrites()

            assertEquals(Source.ICON_PACK.ordinal, saved[PrimarySourceKey])
            assertEquals("com.example.pack", saved[PrimaryIconPackKey])
        }
    }

    @Test
    fun setPrimarySource_withoutAPackKeepsTheStoredOne() = runBlocking {
        withStore("primary-source-keep.preferences_pb") { store ->
            store.setPrimarySource(Source.ICON_PACK, "com.example.pack")
            // Sources other than a pack pass null; the previous pack must not be cleared, so a
            // switch back to Icon pack still remembers it.
            store.setPrimarySource(Source.APPLICATION_ICON, null)
            val saved = store.getPreferencesAfterPendingWrites()

            assertEquals(Source.APPLICATION_ICON.ordinal, saved[PrimarySourceKey])
            assertEquals("com.example.pack", saved[PrimaryIconPackKey])
        }
    }

    @Test
    fun switchProfilePrefs_blocksUiWritesUntilTheTargetIsRestored() = runBlocking {
        withStore("profile-switch-lock.preferences_pb") { store ->
            store.setPrimarySource(Source.ICON_PACK, "leaving.pack")
            val snapshotPersistStarted = CompletableDeferred<Unit>()
            val allowSnapshotPersist = CompletableDeferred<Unit>()

            val switch = async {
                store.switchProfilePrefs(
                    """{"PRIMARY_SOURCE":1,"PRIMARY_ICON_PACK":"target.pack"}""",
                    newProfileId = 2L
                ) {
                    snapshotPersistStarted.complete(Unit)
                    allowSnapshotPersist.await()
                }
            }
            snapshotPersistStarted.await()

            val uiWrite = async {
                store.setPrimarySource(Source.ICON_PACK, "new.target.pack")
            }
            assertFalse(uiWrite.isCompleted)

            allowSnapshotPersist.complete(Unit)
            switch.await()
            uiWrite.await()

            val saved = store.getPreferencesAfterPendingWrites()
            assertEquals(2L, saved[ActiveProfileIdKey])
            assertEquals(Source.ICON_PACK.ordinal, saved[PrimarySourceKey])
            assertEquals("new.target.pack", saved[PrimaryIconPackKey])
        }
    }

    @Test
    fun setColorStyle_writesTheWholeStyleAtOnce() = runBlocking {
        withStore { store ->
            store.setColorStyle(
                ColorizerStyleKeys,
                mode = 1,
                gradientType = 1,
                gradientAngle = 137,
                firstColor = Color.Red,
                gradientStops = listOf(android.graphics.Color.BLUE),
                gradientPositions = listOf(0f, 0.75f)
            )
            val saved = store.getPreferencesAfterPendingWrites()

            assertEquals(listOf(0f, 0.75f), saved.getGradientPositions(ColorizerGradientPositionsKey))
            assertEquals(1, saved[ColorizerModeKey])
            assertEquals(1, saved[ColorizerGradientTypeKey])
            assertEquals(137, saved[ColorizerGradientAngleKey])
            assertEquals(Color.Red.toArgb(), saved.getColorValue(IconColorKey, Color.White).toArgb())
            assertEquals(
                listOf(android.graphics.Color.BLUE),
                saved.getGradientStops(ColorizerGradientColorsKey, ColorizerGradientColorKey)
            )
            // The legacy single-stop key stays in sync for older builds.
            assertEquals(
                android.graphics.Color.BLUE,
                saved.getColorValue(ColorizerGradientColorKey, Color.White).toArgb()
            )
        }
    }

    @Test
    fun setColorStyle_keepsTheOutlinesFirstColour() = runBlocking {
        withStore { store ->
            // The outline's legacy stop key IS its first-colour key: syncing it would overwrite
            // the first colour with the second.
            store.setColorStyle(
                OutlineStyleKeys,
                mode = 1,
                gradientType = 0,
                gradientAngle = 0,
                firstColor = Color.Red,
                gradientStops = listOf(android.graphics.Color.BLUE)
            )
            val saved = store.getPreferencesAfterPendingWrites()

            assertEquals(
                Color.Red.toArgb(),
                saved.getColorValue(OutlineColorKey, Color.White).toArgb()
            )
            assertEquals(
                listOf(android.graphics.Color.BLUE),
                saved.getGradientStops(OutlineGradientColorsKey, OutlineColorKey)
            )
        }
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
                GlobalColorizerModeKey to 1,
                GlobalColorizerGradientColorKey to "#FF123456",
                GlobalColorizerGradientAngleKey to 248,
                GlobalColorizerGradientTypeKey to 1,
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
            assertEquals(1, saved[GlobalColorizerModeKey])
            assertEquals("#FF123456", saved[GlobalColorizerGradientColorKey])
            assertEquals(248, saved[GlobalColorizerGradientAngleKey])
            assertEquals(1, saved[GlobalColorizerGradientTypeKey])
            assertFalse(saved[GlobalApplyGeneratedKey] == true)
            assertTrue(saved[GlobalApplyExistingKey] == true)
            assertTrue(saved[GlobalApplyCustomKey] == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistGlobalStylePrefs_updatesSourcesAndModifiersButKeepsOtherPrefs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.root.resolve("global-style.preferences_pb")
        }
        try {
            store.setStringValue(TextFontKey, "keep-font.ttf")
            val staged = preferencesOf(
                PrimarySourceKey to Source.ICON_PACK.ordinal,
                PrimaryIconPackKey to "primary.pack",
                SecondarySourceKey to Source.APPLICATION_ICON.ordinal,
                SecondaryIconPackKey to "secondary.pack",
                FallbackSourceKey to FallbackSource.PRIMARY.ordinal,
                GlobalIconScaleKey to 73,
                TextFontKey to "staged-font.ttf"
            )

            store.persistGlobalStylePrefs(staged)
            val saved = store.getPreferencesAfterPendingWrites()

            assertEquals(Source.ICON_PACK.ordinal, saved[PrimarySourceKey])
            assertEquals("primary.pack", saved[PrimaryIconPackKey])
            assertEquals(Source.APPLICATION_ICON.ordinal, saved[SecondarySourceKey])
            assertEquals("secondary.pack", saved[SecondaryIconPackKey])
            assertEquals(FallbackSource.PRIMARY.ordinal, saved[FallbackSourceKey])
            assertEquals(73, saved[GlobalIconScaleKey])
            assertEquals("keep-font.ttf", saved[TextFontKey])
        } finally {
            scope.cancel()
        }
    }
}
