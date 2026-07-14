package dev.renkinProject.renkin.service

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.watch.AppComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchActivityResolverTest {

    private val stored = AppComponent("com.example", "OldActivity")

    @Test
    fun exactActivityWinsEvenWhenPackageHasMultipleLaunchers() {
        val resolved = resolveWatchApp(
            stored,
            listOf(app("OtherActivity"), app("OldActivity"))
        )

        assertEquals("OldActivity", resolved?.application?.activityName)
        assertFalse(resolved!!.componentChanged)
    }

    @Test
    fun soleReplacementIsSelectedForMigration() {
        val resolved = resolveWatchApp(stored, listOf(app("NewActivity")))

        assertEquals("NewActivity", resolved?.application?.activityName)
        assertTrue(resolved!!.componentChanged)
    }

    @Test
    fun multipleReplacementsRemainUnresolved() {
        assertNull(resolveWatchApp(stored, listOf(app("FirstActivity"), app("SecondActivity"))))
    }

    @Test
    fun missingPackageRemainsDormant() {
        assertNull(resolveWatchApp(stored, emptyList()))
    }

    private fun app(activityName: String) = InstalledApplication("com.example", activityName, 0)
}
