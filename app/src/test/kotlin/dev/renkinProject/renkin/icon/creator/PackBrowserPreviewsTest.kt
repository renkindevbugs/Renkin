package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.icon.creator.PackBrowserPreviews.Companion.cacheKey
import dev.renkinProject.renkin.icon.creator.PackBrowserPreviews.Companion.orderDrawableNames
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
        val a = cacheKey("com.pack", IconSortOrder.NAME_ASC, "q", options(), null)
        val b = cacheKey("com.pack", IconSortOrder.NAME_ASC, "q", options(), null)
        assertEquals(a, b)
    }

    @Test
    fun cacheKey_differsByEachInput() {
        val base = cacheKey("com.pack", IconSortOrder.NAME_ASC, "q", options(), null)
        assertNotEquals(base, cacheKey("com.other", IconSortOrder.NAME_ASC, "q", options(), null))
        assertNotEquals(base, cacheKey("com.pack", IconSortOrder.NAME_DESC, "q", options(), null))
        assertNotEquals(base, cacheKey("com.pack", IconSortOrder.NAME_ASC, "other", options(), null))
        assertNotEquals(base, cacheKey("com.pack", IconSortOrder.NAME_ASC, "q", options(color = 0xFFFF0000.toInt()), null))
        assertNotEquals(
            base,
            cacheKey("com.pack", IconSortOrder.NAME_ASC, "q", options(), InstalledApplication("com.app", "com.app.Main", 0))
        )
    }

    @Test
    fun cacheKey_distinguishesActivitiesInTheSamePackage() {
        val first = cacheKey(
            "com.pack", IconSortOrder.NAME_ASC, "q", options(),
            InstalledApplication("com.app", "com.app.First", 0)
        )
        val second = cacheKey(
            "com.pack", IconSortOrder.NAME_ASC, "q", options(),
            InstalledApplication("com.app", "com.app.Second", 0)
        )

        assertNotEquals(first, second)
    }
}
