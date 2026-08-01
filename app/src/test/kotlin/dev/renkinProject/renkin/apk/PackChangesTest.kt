package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What "changes since last build" reports. The comparison has to hold across a restart, so it
 * leans on the stored fingerprints rather than on session markers alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackChangesTest {

    private fun app(
        name: String,
        withIcon: Boolean = true,
        refreshMade: Boolean = false
    ) = PackageInfoStruct(
        appName = name,
        packageName = "com.$name",
        activityName = "com.$name.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = if (withIcon) {
            BitmapIconDrawable(
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888),
                false
            )
        } else null,
        isRefreshMade = refreshMade
    )

    private val signal = app("signal")
    private val firefox = app("firefox")

    @Test
    fun anIconTheBuildNeverSawIsAdded() {
        val changes = packChanges(listOf(signal), emptyMap(), mapOf(signal.key to "a"), emptySet())

        assertEquals(1, changes.size)
        assertEquals(PackChangeKind.ADDED, changes.first().kind)
    }

    @Test
    fun aStoredIconWhoseFingerprintMovedOnIsChanged() {
        // The session markers are empty on purpose: this is the after-a-restart case that the
        // key sets alone could not answer.
        val changes = packChanges(
            listOf(signal),
            builtHashes = mapOf(signal.key to "built"),
            savedHashes = mapOf(signal.key to "saved"),
            updatedKeys = emptySet()
        )

        assertEquals(PackChangeKind.CHANGED, changes.single().kind)
        assertEquals(PackChangeReason.HAND_EDIT, changes.single().reason)
    }

    @Test
    fun anUnsavedRefreshIsChangedEvenThoughTheStoredRowStillMatches() {
        val refreshed = app("signal", refreshMade = true)

        val changes = packChanges(
            listOf(refreshed),
            builtHashes = mapOf(refreshed.key to "same"),
            savedHashes = mapOf(refreshed.key to "same"),
            updatedKeys = emptySet()
        )

        assertEquals(PackChangeKind.CHANGED, changes.single().kind)
        assertEquals(PackChangeReason.REFRESH, changes.single().reason)
    }

    @Test
    fun anIconTheBuildShippedAndTheUserClearedIsRemoved() {
        val cleared = app("signal", withIcon = false)

        val changes = packChanges(
            listOf(cleared),
            builtHashes = mapOf(cleared.key to "built"),
            savedHashes = emptyMap(),
            updatedKeys = emptySet()
        )

        assertEquals(PackChangeKind.REMOVED, changes.single().kind)
        assertEquals(PackChangeReason.ICON_REMOVED, changes.single().reason)
    }

    @Test
    fun anAppThatNeverHadAnIconIsNotAChange() {
        val empty = app("signal", withIcon = false)

        assertTrue(packChanges(listOf(empty), emptyMap(), emptyMap(), emptySet()).isEmpty())
    }

    @Test
    fun anUntouchedBuiltIconIsNotAChange() {
        val changes = packChanges(
            listOf(signal),
            builtHashes = mapOf(signal.key to "same"),
            savedHashes = mapOf(signal.key to "same"),
            updatedKeys = emptySet()
        )

        assertTrue(changes.isEmpty())
    }

    @Test
    fun handEditsOutrankTheRefreshMarker() {
        val refreshed = app("signal", refreshMade = true)

        val changes = packChanges(
            listOf(refreshed),
            builtHashes = mapOf(refreshed.key to "built"),
            savedHashes = mapOf(refreshed.key to "built"),
            updatedKeys = setOf(refreshed.key)
        )

        // An icon the user picked after a refresh is theirs, whatever the marker still says.
        assertEquals(PackChangeReason.HAND_EDIT, changes.single().reason)
    }

    @Test
    fun groupsComeAddedThenChangedThenRemoved() {
        val cleared = app("zeta", withIcon = false)
        val changes = packChanges(
            listOf(cleared, signal, firefox),
            builtHashes = mapOf(cleared.key to "built", firefox.key to "old"),
            savedHashes = mapOf(firefox.key to "new"),
            updatedKeys = emptySet()
        )

        assertEquals(
            listOf(PackChangeKind.ADDED, PackChangeKind.CHANGED, PackChangeKind.REMOVED),
            changes.map { it.kind }
        )
    }

    @Test
    fun theFingerprintOnlyFollowsWhatGetsExported() {
        val a = iconFingerprint("pixels", calendarEnabled = false, calendarPrefix = "")
        val same = iconFingerprint("pixels", calendarEnabled = false, calendarPrefix = null)
        val calendar = iconFingerprint("pixels", calendarEnabled = true, calendarPrefix = "cal_")

        assertEquals(a, same)
        assertTrue(a != calendar)
        // Nothing exported, nothing to compare.
        assertEquals("", iconFingerprint("", calendarEnabled = false, calendarPrefix = ""))
    }
}
