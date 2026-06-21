package dev.alembiconsProject.alembicons.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class DataPreferencesTest {

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
}
