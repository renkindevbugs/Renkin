package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UiSummaryPerformanceTest {

    private fun app(
        name: String,
        hasIcon: Boolean,
        fallback: Boolean = false
    ) = PackageInfoStruct(
        appName = name,
        packageName = "com.example.${name.lowercase()}",
        activityName = "com.example.$name.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = if (hasIcon) {
            BitmapIconDrawable(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        } else {
            null
        },
        isFallback = fallback
    )

    @Test
    fun heroStats_countBuiltAddedRemovedAndFallbackAppsInOneResult() {
        val built = app("Built", hasIcon = true)
        val added = app("Added", hasIcon = true, fallback = true)
        val removed = app("Removed", hasIcon = false)
        val untouched = app("Untouched", hasIcon = false)

        val result = calculateHeroPackStats(
            apps = listOf(built, added, removed, untouched),
            builtKeys = setOf(built.key, removed.key)
        )

        assertEquals(
            HeroPackStats(
                builtCount = 1,
                addedCount = 1,
                removedCount = 1,
                themedCount = 2,
                totalCount = 4,
                fallbackCount = 1,
                missingCount = 2,
                lockedCount = 0
            ),
            result
        )
    }

    @Test
    fun heroStats_countsLockedAppsSeparatelyFromMissingOnes() {
        // The hero card's toggle group labels these two groups separately, and the list filters
        // split them the same way — a locked app must not also be counted as missing.
        val locked = app("Locked", hasIcon = false)
        val missing = app("Missing", hasIcon = false)
        val themed = app("Themed", hasIcon = true)

        val result = calculateHeroPackStats(
            apps = listOf(locked, missing, themed),
            builtKeys = emptySet(),
            lockedKeys = setOf(locked.key)
        )

        assertEquals(1, result.missingCount)
        assertEquals(1, result.lockedCount)
        assertEquals(1, result.themedCount)
    }

    @Test
    fun buildPreview_keepsOnlyThemedAppsAndPreservesPriorityOrder() {
        val existing = app("Beta", hasIcon = true)
        val updated = app("Zulu", hasIcon = true)
        val newSecond = app("Delta", hasIcon = true)
        val newFirst = app("Alpha", hasIcon = true)
        val unthemed = app("Hidden", hasIcon = false)

        val result = buildPreviewApps(
            applications = listOf(existing, updated, newSecond, newFirst, unthemed),
            builtKeys = setOf(existing.key, updated.key, unthemed.key),
            updatedKeys = setOf(updated.key)
        )

        assertEquals(2, result.newCount)
        assertEquals(
            listOf("Alpha", "Delta", "Zulu", "Beta"),
            result.applications.map { it.appName }
        )
    }

    @Test
    fun pendingChangesChip_waitsForProfileSummaryAndFinalProgressAnimation() {
        assertFalse(
            shouldShowPendingChanges(
                profileSummaryReady = false,
                progressAnimationSettled = true,
                changeCount = 1,
                hasUnbuiltChanges = false
            )
        )
        assertFalse(
            shouldShowPendingChanges(
                profileSummaryReady = true,
                progressAnimationSettled = false,
                changeCount = 1,
                hasUnbuiltChanges = false
            )
        )
        assertTrue(
            shouldShowPendingChanges(
                profileSummaryReady = true,
                progressAnimationSettled = true,
                changeCount = 1,
                hasUnbuiltChanges = false
            )
        )
    }
}
