package dev.renkinProject.renkin.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchExtensionTest {
    @Test
    fun lowercasesAndUnderscoresSpaces() {
        assertEquals("hbo_max", "HBO Max".normalizeIconSearchQuery())
    }

    @Test
    fun nonBreakingSpaceIsTreatedLikeASpace() {
        // The exact bug: an app label with a non-breaking space (U+00A0) used to stay unmatched
        // because only the ASCII space was replaced.
        assertEquals("hbo_max", "HBO Max".normalizeIconSearchQuery())
    }

    @Test
    fun collapsesAndTrimsWhitespaceRuns() {
        assertEquals("google_play", "  Google   Play  ".normalizeIconSearchQuery())
    }

    @Test
    fun trailingSpaceDoesNotBreakMatching() {
        assertEquals("hbo", "HBO ".normalizeIconSearchQuery())
        assertEquals("hbo", "HBO ".normalizeIconSearchQuery())
    }

    @Test
    fun emptyAndBlankReturnEmpty() {
        assertEquals("", "".normalizeIconSearchQuery())
        assertEquals("", "   ".normalizeIconSearchQuery())
    }

    @Test
    fun keepsExistingUnderscores() {
        assertEquals("two_letters", "two_letters".normalizeIconSearchQuery())
    }
}
