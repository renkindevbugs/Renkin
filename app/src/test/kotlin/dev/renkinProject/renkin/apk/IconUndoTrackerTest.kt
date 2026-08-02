package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules behind the Undo snackbar: one step at a time, never across profiles, and never for
 * apps that are no longer in the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconUndoTrackerTest {

    private fun app(pkg: String) = PackageInfoStruct(
        appName = pkg,
        packageName = pkg,
        activityName = "$pkg.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0
    )

    private val signal = app("com.signal")
    private val firefox = app("com.firefox")
    private val current = listOf(signal, firefox)

    @Test
    fun capturedRowsAreRestoredWhereTheyNowSit() {
        val tracker = IconUndoTracker()
        tracker.capture(listOf(firefox), profileId = 1L, persisted = false)

        val restoration = tracker.restorationFor(current, activeProfileId = 1L)

        assertEquals(1, tracker.size)
        assertEquals(listOf(1 to firefox), restoration)
    }

    @Test
    fun capturingNothingWithdrawsTheOffer() {
        val tracker = IconUndoTracker()
        tracker.capture(listOf(signal), profileId = 1L, persisted = true)

        tracker.capture(emptyList(), profileId = 1L, persisted = true)

        assertNull(tracker.step)
        assertEquals(0, tracker.size)
        assertTrue(tracker.restorationFor(current, 1L).isEmpty())
    }

    @Test
    fun aSecondChangeReplacesTheFirst() {
        val tracker = IconUndoTracker()
        tracker.capture(listOf(signal), profileId = 1L, persisted = false)

        tracker.capture(listOf(firefox), profileId = 1L, persisted = true)

        // Only ever one step back, exactly like the snackbar that offers it.
        assertEquals(listOf(1 to firefox), tracker.restorationFor(current, 1L))
        assertEquals(true, tracker.step?.persisted)
    }

    @Test
    fun anotherProfileCannotRestoreTheStep() {
        val tracker = IconUndoTracker()
        tracker.capture(listOf(signal), profileId = 1L, persisted = false)

        // The rows describe profile 1's icons; applying them to profile 2 would import them.
        assertTrue(tracker.restorationFor(current, activeProfileId = 2L).isEmpty())
    }

    @Test
    fun uninstalledAppsAreSkippedAndTheRestStillGoesBack() {
        val gone = app("com.uninstalled")
        val tracker = IconUndoTracker()
        tracker.capture(listOf(gone, signal), profileId = 1L, persisted = false)

        assertEquals(listOf(0 to signal), tracker.restorationFor(current, 1L))
    }

    @Test
    fun clearWithdrawsTheOffer() {
        val tracker = IconUndoTracker()
        tracker.capture(listOf(signal), profileId = 1L, persisted = false)

        tracker.clear()

        assertNull(tracker.step)
        assertTrue(tracker.restorationFor(current, 1L).isEmpty())
    }
}
