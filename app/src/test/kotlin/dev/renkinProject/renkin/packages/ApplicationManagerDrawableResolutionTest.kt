package dev.renkinProject.renkin.packages

import android.app.Application
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApplicationManagerDrawableResolutionTest {

    @Test
    fun missingName_doesNotShiftFollowingNameToTheWrongResource() {
        val ids = mapOf("first" to 11, "missing" to 0, "last" to 33)

        val result = resolveNamedDrawables(
            listOf("first", "missing", "last"),
            resolveId = { ids.getValue(it) },
            loadDrawable = { ColorDrawable(it) }
        )

        assertEquals(listOf("first", "last"), result.map { it.name })
        assertEquals(listOf(11, 33), result.map { it.resource.resourceId })
    }

    @Test
    fun malformedDrawable_skipsOnlyThatEntry() {
        val result = resolveNamedDrawables(
            listOf("first", "broken", "last"),
            resolveId = { name -> mapOf("first" to 11, "broken" to 22, "last" to 33).getValue(name) },
            loadDrawable = { id -> if (id == 22) error("inflate failed") else ColorDrawable(id) }
        )

        assertEquals(listOf("first", "last"), result.map { it.name })
        assertEquals(listOf(11, 33), result.map { it.resource.resourceId })
    }

    @Test
    fun componentLookup_mapsOnlyMatchingApplications() {
        val first = InstalledApplication("com.first", "com.first.Main", 1)
        val second = InstalledApplication("com.second", "com.second.Main", 2)
        val unmatchedDrawable = ResourceDrawable(30, ColorDrawable(30))
        val firstDrawable = ResourceDrawable(10, ColorDrawable(10))

        val result = matchDrawablesToApplications(
            listOf(first, second),
            linkedMapOf(
                first.toComponentInfo() to firstDrawable,
                "ComponentInfo{com.missing/com.missing.Main}" to unmatchedDrawable
            )
        )

        assertEquals(setOf(first), result.keys)
        assertSame(firstDrawable, result[first])
        assertNull(result[second])
    }

    @Test
    fun targetedLookup_usesLastItemDeclarationAndIgnoresOtherElementTypes() {
        val component = "ComponentInfo{com.app/com.app.Main}"
        val first = RawItem(component, "old_icon")
        val last = RawItem(component, "new_icon")

        val result = lastAppFilterItem(
            listOf(
                first,
                RawCalendar(component, "calendar_"),
                RawItem("ComponentInfo{com.other/com.other.Main}", "other_icon"),
                last
            ),
            component
        )

        assertSame(last, result)
    }
}
