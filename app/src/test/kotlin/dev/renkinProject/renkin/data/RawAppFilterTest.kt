package dev.renkinProject.renkin.data

import dev.renkinProject.renkin.apk.AppFilterIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawAppFilterTest {
    private val maps = InstalledApplication("com.google.maps", "Main", 0)
    private val elements = listOf(
        RawItem(maps.toComponentInfo(), "google_maps"),
        RawCalendar(maps.toComponentInfo(), "cal_"),
        RawItem(maps.toComponentInfo(), "maps_alt"),
        RawItem(maps.toComponentInfo(), "google_maps"),
        RawItem("ComponentInfo{com.other/Main}", "other")
    )

    @Test fun drawableNamesForKeepsOnlyTheAppsItemsOnceInPackOrder() {
        assertEquals(listOf("google_maps", "maps_alt"), elements.drawableNamesFor(maps))
    }

    @Test fun browserNamesForNeedsAnIndexedPackAndApp() {
        val index = AppFilterIndex(mapOf("pack" to elements), listOf(maps))

        assertEquals(listOf("google_maps", "maps_alt"), index.browserNamesFor("pack", maps))
        assertNull(index.browserNamesFor("missing", maps))
        assertNull(index.browserNamesFor("pack", InstalledApplication("com.unknown", "Main", 0)))
    }
}
