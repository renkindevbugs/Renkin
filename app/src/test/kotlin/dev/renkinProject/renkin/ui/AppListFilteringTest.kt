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
        installTimes: Map<String, Long> = emptyMap()
    ) = sortedFilteredApps(query, filterNoIcon, filterFallback, sortOrder, installTimes) { it }
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
    fun fallbackFilterTakesPrecedenceOverNoIcon() {
        // Both flags on: fallback wins (matches the AppSortFilterMenu's mutually exclusive choice).
        val apps = listOf(app("NoIcon", hasIcon = false), app("Fallback", fallback = true))
        assertEquals(listOf("Fallback"), apps.run(filterNoIcon = true, filterFallback = true))
    }
}
