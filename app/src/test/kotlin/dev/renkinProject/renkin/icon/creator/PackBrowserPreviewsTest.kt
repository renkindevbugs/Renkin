package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.icon.creator.PackBrowserPreviews.Companion.cacheKey
import dev.renkinProject.renkin.icon.creator.PackBrowserPreviews.Companion.orderDrawableNames
import dev.renkinProject.renkin.packages.NamedResourceDrawable
import dev.renkinProject.renkin.packages.PackBrowserDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic of the icon-pack browser: how a pack's drawable names are filtered, sorted and
 * ordered (component-mapped icons first), and how the row cache key is built. No Android needed —
 * that's the point of pulling this out of MainViewModel.
 */
class PackBrowserPreviewsTest {

    private val names = listOf("gmail", "google_maps", "maps_pin", "calendar")
    private fun pack(versionCode: Long = 1) = IconPack("com.pack", "Pack", versionCode, "$versionCode", 0)

    @Test
    fun emptyQuery_returnsAllSortedAscending() {
        val result = orderDrawableNames(names, emptyList(), "", IconSortOrder.NAME_ASC)
        assertEquals(listOf("calendar", "gmail", "google_maps", "maps_pin"), result)
    }

    @Test
    fun nameDesc_sortsDescending() {
        val result = orderDrawableNames(names, emptyList(), "", IconSortOrder.NAME_DESC)
        assertEquals(listOf("maps_pin", "google_maps", "gmail", "calendar"), result)
    }

    @Test
    fun query_filtersBySubstring_caseAndWhitespaceNormalized() {
        // "MAP" lowercases to "map"; only names containing it survive, still sorted.
        val result = orderDrawableNames(names, emptyList(), "MAP", IconSortOrder.NAME_ASC)
        assertEquals(listOf("google_maps", "maps_pin"), result)
    }

    @Test
    fun query_whitespaceBecomesUnderscore() {
        // The search normalises spaces to underscores, matching drawable naming.
        val result = orderDrawableNames(names, emptyList(), "google maps", IconSortOrder.NAME_ASC)
        assertEquals(listOf("google_maps"), result)
    }

    @Test
    fun componentNames_leadAndAreNotDuplicated() {
        val result = orderDrawableNames(names, listOf("google_maps"), "", IconSortOrder.NAME_ASC)
        assertEquals("google_maps", result.first())
        assertEquals(1, result.count { it == "google_maps" })
        assertEquals(names.size, result.size)
    }

    @Test
    fun componentNames_leadEvenWhenQueryWouldExcludeThem() {
        // The component-mapped icon must appear regardless of the name query — packs map apps by
        // component, so the designated icon is found even if its name doesn't match the search.
        val result = orderDrawableNames(names, listOf("calendar"), "map", IconSortOrder.NAME_ASC)
        assertEquals("calendar", result.first())
        assertTrue(result.containsAll(listOf("google_maps", "maps_pin")))
    }

    private fun options(color: Int = 0xFF000000.toInt()) = GenerationOptions(
        primarySource = Source.APPLICATION_NAME,
        primaryImageEdit = ImageEdit.NONE,
        primaryTextType = TextType.FULL_NAME,
        primaryIconPack = "",
        color = color,
        bgColor = 0,
        vector = false,
        materialYou = false,
        themed = false,
        override = true
    )

    @Test
    fun cacheKey_sameInputsProduceSameKey() {
        val a = cacheKey(pack(), IconSortOrder.NAME_ASC, "q", options(), null)
        val b = cacheKey(pack(), IconSortOrder.NAME_ASC, "q", options(), null)
        assertEquals(a, b)
    }

    @Test
    fun cacheKey_differsByEachInput() {
        val base = cacheKey(pack(), IconSortOrder.NAME_ASC, "q", options(), null)
        assertNotEquals(base, cacheKey(IconPack("com.other", "Other", 1, "1", 0), IconSortOrder.NAME_ASC, "q", options(), null))
        assertNotEquals(base, cacheKey(pack(), IconSortOrder.NAME_DESC, "q", options(), null))
        assertNotEquals(base, cacheKey(pack(), IconSortOrder.NAME_ASC, "other", options(), null))
        assertNotEquals(base, cacheKey(pack(), IconSortOrder.NAME_ASC, "q", options(color = 0xFFFF0000.toInt()), null))
        assertNotEquals(
            base,
            cacheKey(pack(), IconSortOrder.NAME_ASC, "q", options(), InstalledApplication("com.app", "com.app.Main", 0))
        )
    }

    @Test
    fun cacheKey_changesWhenInstalledPackVersionChanges() {
        val old = cacheKey(pack(versionCode = 1), IconSortOrder.NAME_ASC, "", options(), null)
        val updated = cacheKey(pack(versionCode = 2), IconSortOrder.NAME_ASC, "", options(), null)

        assertNotEquals(old, updated)
    }

    @Test
    fun cacheKey_changesWhenMaterialYouCapabilityChanges() {
        val ordinary = pack()
        val materialYou = ordinary.copy(changesWithMaterialYouColors = true)

        assertNotEquals(
            cacheKey(ordinary, IconSortOrder.NAME_ASC, "", options(), null),
            cacheKey(materialYou, IconSortOrder.NAME_ASC, "", options(), null)
        )
    }

    @Test
    fun cacheKey_distinguishesActivitiesInTheSamePackage() {
        val first = cacheKey(
            pack(), IconSortOrder.NAME_ASC, "q", options(),
            InstalledApplication("com.app", "com.app.First", 0)
        )
        val second = cacheKey(
            pack(), IconSortOrder.NAME_ASC, "q", options(),
            InstalledApplication("com.app", "com.app.Second", 0)
        )

        assertNotEquals(first, second)
    }

    @Test
    fun failedRowLoad_isNotCachedAndCanRetry() = runBlocking {
        var calls = 0
        val source = object : PackBrowserDataSource {
            override fun getIconPackDrawableNames(iconPackName: String): List<String> {
                calls++
                if (calls == 1) error("broken drawable.xml")
                return emptyList()
            }

            override fun getIconPackDrawableEntries(
                iconPackName: String,
                drawableNames: List<String>
            ): List<NamedResourceDrawable> = emptyList()

            override fun getAppFilterRawElements(
                iconPackName: String,
                applications: List<InstalledApplication>
            ): List<RawElement> = emptyList()
        }
        val previews = PackBrowserPreviews(source) { _, _, _ -> emptyMap() }

        previews.rowPreviews(pack(), IconSortOrder.NAME_ASC, "", options())
        previews.rowPreviews(pack(), IconSortOrder.NAME_ASC, "", options())

        assertEquals(2, calls)
    }

    @Test
    fun clear_invalidatesSuccessfulRowCache() = runBlocking {
        var calls = 0
        val source = object : PackBrowserDataSource {
            override fun getIconPackDrawableNames(iconPackName: String): List<String> {
                calls++
                return emptyList()
            }

            override fun getIconPackDrawableEntries(
                iconPackName: String,
                drawableNames: List<String>
            ): List<NamedResourceDrawable> = emptyList()

            override fun getAppFilterRawElements(
                iconPackName: String,
                applications: List<InstalledApplication>
            ): List<RawElement> = emptyList()
        }
        val previews = PackBrowserPreviews(source) { _, _, _ -> emptyMap() }

        previews.rowPreviews(pack(), IconSortOrder.NAME_ASC, "", options())
        previews.rowPreviews(pack(), IconSortOrder.NAME_ASC, "", options())
        previews.clear()
        previews.rowPreviews(pack(), IconSortOrder.NAME_ASC, "", options())

        assertEquals(2, calls)
    }
}
