package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.InstalledApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationProviderComponentTest {

    @Test
    fun perAppCalendar_replacesOnlyTheMatchingComponent() {
        val globalFirst = InstalledApplication("com.app", "com.app.First", 1)
        val globalSecond = InstalledApplication("com.app", "com.app.Second", 2)
        // A resource-id change must not change component identity.
        val perAppFirst = InstalledApplication("com.app", "com.app.First", 99)

        val merged = mergeCalendarIconMappings(
            global = linkedMapOf(globalFirst to "global_first_", globalSecond to "global_second_"),
            perApp = mapOf(perAppFirst to "picked_first_")
        )

        assertEquals(2, merged.size)
        assertFalse(globalFirst in merged)
        assertEquals("picked_first_", merged[perAppFirst])
        assertEquals("global_second_", merged[globalSecond])
        assertTrue(globalSecond in merged)
    }
}
