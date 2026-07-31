package dev.renkinProject.renkin.data

import android.app.Application
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bundled gradient library: what survives parsing, how it is grouped, and how it filters. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GradientPresetsTest {

    @Test
    fun parseKeepsUsableEntriesOnly() {
        val eleven = (1..11).joinToString(",") { "\"#0000FF\"" }
        val json = """
            [
              {"name":"Two","colors":["#FF0000","#00FF00"]},
              {"name":"Single","colors":["#FF0000"]},
              {"name":"","colors":["#FF0000","#00FF00"]},
              {"name":"Broken","colors":["nonsense","also bad"]},
              {"name":"Eleven","colors":[$eleven]}
            ]
        """.trimIndent()

        val parsed = GradientPresets.parse(json)

        // A single stop is not a gradient, a nameless entry has nothing to search by, unparseable
        // colours leave nothing to paint, and eleven stops is past the editor's limit.
        assertEquals(listOf("Two"), parsed.map { it.name })
        assertEquals(listOf(Color.RED, Color.GREEN), parsed.first().colors)
    }

    @Test
    fun parseDropsDuplicateNames() {
        val json = """
            [
              {"name":"Twin","colors":["#FF0000","#00FF00"]},
              {"name":"Twin","colors":["#0000FF","#FFFFFF"]}
            ]
        """.trimIndent()

        // The name is the grid's key; a duplicate would crash the list rather than show twice.
        assertEquals(1, GradientPresets.parse(json).size)
    }

    @Test
    fun parseSurvivesGarbage() {
        assertEquals(emptyList<GradientPreset>(), GradientPresets.parse(""))
        assertEquals(emptyList<GradientPreset>(), GradientPresets.parse("{\"not\":\"an array\"}"))
    }

    @Test
    fun familiesFollowTheColoursThemselves() {
        assertEquals(GradientFamily.WARM, gradientFamilyOf(listOf(0xFFFF4500.toInt(), 0xFFFFA500.toInt())))
        assertEquals(GradientFamily.COOL, gradientFamilyOf(listOf(0xFF2196F3.toInt(), 0xFF00BCD4.toInt())))
        assertEquals(GradientFamily.DARK, gradientFamilyOf(listOf(0xFF091E3A.toInt(), 0xFF1B2735.toInt())))
        assertEquals(GradientFamily.MONO, gradientFamilyOf(listOf(0xFFEEEEEE.toInt(), 0xFF999999.toInt())))
        assertEquals(GradientFamily.PASTEL, gradientFamilyOf(listOf(0xFFFFD3A5.toInt(), 0xFFFFE8D6.toInt())))
    }

    @Test
    fun redsAverageAroundTheHueWheelInsteadOfThroughIt() {
        // 350° and 10° are both red; averaging them arithmetically would land on cyan.
        assertEquals(
            GradientFamily.WARM,
            gradientFamilyOf(listOf(0xFFFF0033.toInt(), 0xFFFF2A00.toInt()))
        )
    }

    @Test
    fun filterCombinesNameFamilyAndStopCount() {
        val warmPair = GradientPreset("Sunset", listOf(0xFFFF4500.toInt(), 0xFFFFA500.toInt()))
        val coolPair = GradientPreset("Sunken", listOf(0xFF2196F3.toInt(), 0xFF00BCD4.toInt()))
        val coolTriple = GradientPreset(
            "Sunlit sea",
            listOf(0xFF2196F3.toInt(), 0xFF00BCD4.toInt(), 0xFF03A9F4.toInt())
        )
        val all = listOf(warmPair, coolPair, coolTriple)

        assertEquals(all, filterGradientPresets(all, "  ", null))
        assertEquals(listOf(warmPair), filterGradientPresets(all, "sunset", null))
        assertEquals(listOf(coolPair, coolTriple), filterGradientPresets(all, "", GradientFamily.COOL))
        assertEquals(listOf(coolTriple), filterGradientPresets(all, "sun", GradientFamily.COOL, 3))
        assertEquals(
            listOf(warmPair, coolPair),
            filterGradientPresets(all, "", null, 2)
        )
    }

    @Test
    fun stopCountChipsOnlyOfferCountsTheLibraryHas() {
        val four = GradientPreset("Four", List(4) { Color.RED })
        val six = GradientPreset("Six", List(6) { Color.BLUE })
        val pair = GradientPreset("Pair", List(2) { Color.GREEN })
        val presets = listOf(four, six, pair)

        // No chip may lead to an empty grid, so five is absent and every offered count matches.
        assertEquals(listOf(2, 4, 6), gradientStopCounts(presets))
        assertEquals(listOf(six), filterGradientPresets(presets, "", null, 6))
        assertTrue(filterGradientPresets(presets, "", null, 5).isEmpty())
    }
}
