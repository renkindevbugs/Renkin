package dev.renkinProject.renkin.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPreferencesTest {
    @Test
    fun freshInstall_reducesNothing() {
        assertEquals(emptySet<MotionPart>(), emptyPreferences().reducedMotionParts())
    }

    @Test
    fun legacySwitchOn_reducesEveryPartNotSetOnItsOwn() {
        val prefs = preferencesOf(ReduceIconMotionKey to true, MotionPart.BUILD.key to false)

        assertEquals(
            setOf(MotionPart.ICON_FLIGHT, MotionPart.EDITOR_TABS, MotionPart.ATTENTION),
            prefs.reducedMotionParts()
        )
    }

    @Test
    fun ownPartValue_winsOverLegacySwitch() {
        val prefs = preferencesOf(ReduceIconMotionKey to false, MotionPart.ATTENTION.key to true)

        assertTrue(prefs.reducesMotion(MotionPart.ATTENTION))
        assertFalse(prefs.reducesMotion(MotionPart.ICON_FLIGHT))
    }

    @Test
    fun partKeys_areValidatedAsBooleans() {
        MotionPart.entries.forEach { part ->
            assertTrue(isValidPersistedPreference(part.key.name, true))
            assertFalse(isValidPersistedPreference(part.key.name, "true"))
        }
    }
}
