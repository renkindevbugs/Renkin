package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pack picker's ordering and its opening scroll position. Both are pure list maths pulled
 * out of the sheet — the ordering in particular went missing once in a refactor without any
 * test noticing, which is what these guard.
 */
class PackPickerOrderTest {

    private fun pack(name: String, pkg: String = name.lowercase()) =
        IconPack(packageName = pkg, applicationName = name, versionCode = 1, versionName = "1", iconID = 0)

    private val lawnicons = pack("Lawnicons")
    private val arcticons = pack("Arcticons")
    private val delta = pack("Delta")

    private val packs = listOf(lawnicons, arcticons, delta)
    private val matched = mapOf(
        lawnicons.packageName to 160,
        arcticons.packageName to 174,
        delta.packageName to 152
    )

    @Test
    fun packsAreOrderedByCoverageDescending() {
        assertEquals(
            listOf("Arcticons", "Lawnicons", "Delta"),
            pickerPacks(packs, matched, "").map { it.applicationName }
        )
    }

    @Test
    fun equalCoverageFallsBackToName() {
        val tie = mapOf(
            lawnicons.packageName to 100,
            arcticons.packageName to 100,
            delta.packageName to 100
        )
        assertEquals(
            listOf("Arcticons", "Delta", "Lawnicons"),
            pickerPacks(packs, tie, "").map { it.applicationName }
        )
    }

    @Test
    fun packWithNoCoverageEntrySortsLast() {
        // A pack whose appfilter failed to parse has no entry at all — it must not vanish.
        val partial = mapOf(arcticons.packageName to 10)
        assertEquals(
            listOf("Arcticons", "Delta", "Lawnicons"),
            pickerPacks(packs, partial, "").map { it.applicationName }
        )
    }

    @Test
    fun queryFiltersByNameCaseInsensitivelyAndKeepsTheOrder() {
        assertEquals(listOf("Lawnicons"), pickerPacks(packs, matched, "law").map { it.applicationName })
        assertEquals(
            listOf("Arcticons", "Lawnicons"),
            pickerPacks(packs, matched, "ICONS").map { it.applicationName }
        )
        assertEquals(emptyList<String>(), pickerPacks(packs, matched, "nothing").map { it.applicationName })
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf("Delta"), pickerPacks(packs, matched, "  delta  ").map { it.applicationName })
    }

    @Test
    fun scrollIndexPointsAtTheSelectedPack() {
        val shown = pickerPacks(packs, matched, "")
        assertEquals(1, pickerScrollIndex(shown, Source.ICON_PACK, lawnicons.packageName, ""))
        assertEquals(0, pickerScrollIndex(shown, Source.ICON_PACK, arcticons.packageName, ""))
    }

    @Test
    fun scrollIndexCoversTheNonPackSourcesAfterTheDivider() {
        val shown = pickerPacks(packs, matched, "")
        assertEquals(shown.size + 1, pickerScrollIndex(shown, Source.NONE, "", ""))
        assertEquals(shown.size + 2, pickerScrollIndex(shown, Source.APPLICATION_ICON, "", ""))
        assertEquals(shown.size + 3, pickerScrollIndex(shown, Source.APPLICATION_NAME, "", ""))
    }

    @Test
    fun noScrollWhileSearchingOrWhenTheSelectedPackIsGone() {
        val shown = pickerPacks(packs, matched, "")
        // Searching: the top of the results is where the user should land.
        assertNull(pickerScrollIndex(shown, Source.ICON_PACK, lawnicons.packageName, "law"))
        // Selected pack uninstalled since it was picked — nothing to scroll to.
        assertNull(pickerScrollIndex(shown, Source.ICON_PACK, "com.gone", ""))
    }
}
