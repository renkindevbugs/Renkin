package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.icon.creator.FontCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The font picker's search. The catalogue order matters — the bundled default is deliberately
 * first — so filtering must narrow the list without reordering it.
 */
class FontPickerFilterTest {

    private val fonts = listOf(
        FontCatalog.DEFAULT,
        FontCatalog.FontChoice("Roboto", "/system/fonts/Roboto-Regular.ttf"),
        FontCatalog.FontChoice("Roboto Condensed", "/system/fonts/RobotoCondensed-Regular.ttf"),
        FontCatalog.FontChoice("Noto Serif", "/system/fonts/NotoSerif-Regular.ttf")
    )

    @Test
    fun emptyQueryKeepsEveryFontInCatalogueOrder() {
        assertEquals(
            listOf("Arcticons Sans", "Roboto", "Roboto Condensed", "Noto Serif"),
            pickerFonts(fonts, "").map { it.label }
        )
    }

    @Test
    fun queryMatchesCaseInsensitivelyAndKeepsTheOrder() {
        assertEquals(
            listOf("Roboto", "Roboto Condensed"),
            pickerFonts(fonts, "ROBO").map { it.label }
        )
    }

    @Test
    fun queryMatchesAnywhereInTheName() {
        assertEquals(listOf("Roboto Condensed"), pickerFonts(fonts, "condensed").map { it.label })
    }

    @Test
    fun bundledDefaultIsSearchableLikeAnySystemFont() {
        assertEquals(listOf("Arcticons Sans"), pickerFonts(fonts, "arcticons").map { it.label })
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf("Noto Serif"), pickerFonts(fonts, "  noto  ").map { it.label })
    }

    @Test
    fun unmatchedQueryReturnsNothing() {
        assertEquals(emptyList<String>(), pickerFonts(fonts, "comic").map { it.label })
    }
}
