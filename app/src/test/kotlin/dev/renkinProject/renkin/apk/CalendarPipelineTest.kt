package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.InstalledApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPipelineTest {

    private fun app(pkg: String, activity: String = "$pkg.Main", iconId: Int = 0) =
        InstalledApplication(pkg, activity, iconId)

    @Test
    fun loadCalendarDays_acceptsZeroPaddedNamesAndNormalizesEveryDay() {
        val available = (1..31).associate { day ->
            "calendar_" + day.toString().padStart(2, '0') to "day-$day"
        }

        val result = loadCalendarDays("calendar_") { available[it] }

        assertEquals(31, result.size)
        assertEquals("day-1", result[1])
        assertEquals("day-9", result[9])
        assertEquals("day-31", result[31])
    }

    @Test
    fun loadCalendarDays_prefersPlainNameAndFillsMissingDays() {
        val available = mapOf(
            "calendar_1" to "plain-one",
            "calendar_01" to "padded-one",
            "calendar_02" to "two"
        )

        val result = loadCalendarDays("calendar_") { available[it] }

        assertEquals(31, result.size)
        assertEquals("plain-one", result[1])
        assertEquals("two", result[2])
        assertEquals("plain-one", result[31])
    }

    @Test
    fun samePrefixFromDifferentPacks_getsIndependentExportNamespaces() {
        val first = app("com.first")
        val second = app("com.second")
        val selections = listOf(
            CalendarSelection(first, "pack.alpha", "calendar_"),
            CalendarSelection(second, "pack.beta", "calendar_")
        )

        val result = buildCalendarData(selections) { pack, _ ->
            (1..31).associateWith { day -> "$pack-$day" }
        }

        val firstPrefix = result.mappings.getValue(first)
        val secondPrefix = result.mappings.getValue(second)
        assertNotEquals(firstPrefix, secondPrefix)
        assertEquals("pack.alpha-1", result.drawables[firstPrefix + "1"])
        assertEquals("pack.beta-1", result.drawables[secondPrefix + "1"])
        assertEquals(62, result.drawables.size)
    }

    @Test
    fun laterPerAppSelection_replacesOnlyTheSameComponent() {
        val globalOverridden = app("com.calendar", "com.calendar.Main", iconId = 1)
        // Resource-id changes do not change launcher-component identity.
        val overridden = app("com.calendar", "com.calendar.Main", iconId = 99)
        val untouched = app("com.other", "com.other.Main")
        val loadedSources = mutableListOf<Pair<String, String>>()
        val result = buildCalendarData(
            listOf(
                CalendarSelection(globalOverridden, "pack.global", "global_"),
                CalendarSelection(untouched, "pack.global", "other_"),
                CalendarSelection(overridden, "pack.custom", "custom_")
            )
        ) { pack, prefix ->
            loadedSources += pack to prefix
            mapOf(1 to pack)
        }

        val overriddenPrefix = result.mappings.getValue(overridden)
        val untouchedPrefix = result.mappings.getValue(untouched)
        assertFalse(globalOverridden in result.mappings)
        assertEquals("pack.custom", result.drawables[overriddenPrefix + "1"])
        assertEquals("pack.global", result.drawables[untouchedPrefix + "1"])
        assertFalse("pack.global" to "global_" in loadedSources)
    }

    @Test
    fun sourceWithoutAnyDayDrawable_doesNotEmitBrokenCalendarMapping() {
        val calendar = app("com.empty")

        val result = buildCalendarData(
            listOf(CalendarSelection(calendar, "pack.empty", "missing_"))
        ) { _, _ -> emptyMap() }

        assertTrue(result.mappings.isEmpty())
        assertTrue(result.drawables.isEmpty())
    }
}
