package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppListFilteringTest {

    private fun icon() = BitmapIconDrawable(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    private fun app(
        name: String,
        pkg: String = name.lowercase(),
        originalName: String = name,
        hasIcon: Boolean = true,
        fallback: Boolean = false
    ) = PackageInfoStruct(
        appName = name,
        packageName = pkg,
        activityName = "$pkg.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = if (hasIcon) icon() else null,
        originalName = originalName,
        isFallback = fallback
    )

    private fun List<PackageInfoStruct>.run(
        query: String = "",
        filterNoIcon: Boolean = false,
        filterFallback: Boolean = false,
        sortOrder: AppSortOrder = AppSortOrder.NAME,
        installTimes: Map<String, Long> = emptyMap(),
        filterLocked: Boolean = false,
        lockedKeys: Set<String> = emptySet()
    ) = sortedFilteredApps(
        query, filterNoIcon, filterFallback, sortOrder, installTimes, filterLocked, lockedKeys
    ) { it }
        .map { it.appName }

    @Test
    fun nameSortIsCaseInsensitiveAlphabetical() {
        val result = listOf(app("banana"), app("Apple"), app("cherry")).run()
        assertEquals(listOf("Apple", "banana", "cherry"), result)
    }

    @Test
    fun installDateSortIsNewestFirst() {
        val apps = listOf(app("Old", pkg = "old"), app("New", pkg = "new"), app("Mid", pkg = "mid"))
        val times = mapOf("old" to 1L, "mid" to 2L, "new" to 3L)
        assertEquals(listOf("New", "Mid", "Old"), apps.run(sortOrder = AppSortOrder.INSTALL_DATE, installTimes = times))
    }

    @Test
    fun queryMatchesLocalizedNameAndOriginalName() {
        val apps = listOf(
            app("Telegram"),
            app("Nastavenia", pkg = "settings", originalName = "Settings")
        )
        // Matches the localized label...
        assertEquals(listOf("Telegram"), apps.run(query = "tele"))
        // ...and the original English name even when the displayed label differs.
        assertEquals(listOf("Nastavenia"), apps.run(query = "settings"))
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf("Discord"), listOf(app("Discord"), app("Slack")).run(query = "  disc  "))
    }

    @Test
    fun noIconFilterKeepsOnlyAppsWithoutAnIcon() {
        val apps = listOf(app("Has", hasIcon = true), app("Missing", hasIcon = false))
        assertEquals(listOf("Missing"), apps.run(filterNoIcon = true))
    }

    @Test
    fun fallbackFilterKeepsOnlyFallbackIcons() {
        val apps = listOf(app("Plain"), app("Framed", fallback = true))
        assertEquals(listOf("Framed"), apps.run(filterFallback = true))
    }

    @Test
    fun combinedFiltersShowTheUnionOfTheirGroups() {
        // Both on: the hero card's toggle group is multi-select, so the list adds the groups
        // instead of one winning — its counts are only honest if this is a union.
        val apps = listOf(app("NoIcon", hasIcon = false), app("Fallback", fallback = true), app("Plain"))
        assertEquals(
            listOf("Fallback", "NoIcon"),
            apps.run(filterNoIcon = true, filterFallback = true)
        )
    }

    @Test
    fun noIconFilterExcludesLockedApps() {
        // A locked app has no icon to show but isn't fixable here — it belongs to the locked
        // group only, so the two groups stay disjoint and their counts add up.
        val locked = app("Locked", hasIcon = false)
        val apps = listOf(app("Missing", hasIcon = false), locked)
        assertEquals(
            listOf("Missing"),
            apps.run(filterNoIcon = true, lockedKeys = setOf(locked.key))
        )
    }

    @Test
    fun lockedFilterKeepsOnlyLockedApps() {
        val locked = app("Locked", hasIcon = false)
        val apps = listOf(app("Missing", hasIcon = false), locked, app("Plain"))
        assertEquals(
            listOf("Locked"),
            apps.run(filterLocked = true, lockedKeys = setOf(locked.key))
        )
    }

    @Test
    fun missingAndLockedTogetherCoverBothGroups() {
        val locked = app("Locked", hasIcon = false)
        val apps = listOf(app("Missing", hasIcon = false), locked, app("Plain"))
        assertEquals(
            listOf("Locked", "Missing"),
            apps.run(filterNoIcon = true, filterLocked = true, lockedKeys = setOf(locked.key))
        )
    }
}
