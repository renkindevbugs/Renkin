package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackRepositoryIsolationTest {
    private val healthy = IconPack("com.healthy", "Healthy", 1, "1", 0)
    private val broken = IconPack("com.broken", "Broken", 1, "1", 0)

    @Test
    fun malformedAppFilter_skipsOnlyItsPack() = runBlocking {
        val failures = mutableListOf<IconPack>()

        val loaded = loadIsolatedAppFilters(
            listOf(broken, healthy),
            loader = { pack ->
                if (pack == broken) error("malformed XML")
                listOf(RawItem("ComponentInfo{com.app/.Main}", "app"))
            },
            onFailure = { pack, _ -> failures += pack }
        )

        assertTrue(healthy in loaded)
        assertFalse(broken in loaded)
        assertEquals(listOf(broken), failures)
    }

    @Test
    fun drawableIndex_keepsLastComponentDeclarationAndIgnoresOtherElements() {
        val component = "ComponentInfo{com.app/.Main}"

        val indexed = indexAppDrawableNames(
            listOf(
                RawItem(component, "old_icon"),
                RawCalendar(component, "calendar_"),
                RawItem("ComponentInfo{com.other/.Main}", "other_icon"),
                RawItem(component, "new_icon")
            )
        )

        assertEquals(
            mapOf(
                component to "new_icon",
                "ComponentInfo{com.other/.Main}" to "other_icon"
            ),
            indexed
        )
    }
}
