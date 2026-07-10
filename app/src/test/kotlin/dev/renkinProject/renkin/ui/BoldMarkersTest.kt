package dev.renkinProject.renkin.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `**bold**` marker parsing dialog bodies rely on (see UIHelper.withBoldMarkers). */
class BoldMarkersTest {

    @Test
    fun markersBecomeSemiBoldSpans_andDisappearFromText() {
        val result = "Delete **“Work”** and all of its icons?".withBoldMarkers()

        assertEquals("Delete “Work” and all of its icons?", result.text)
        val span = result.spanStyles.single()
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
        assertEquals("“Work”", result.text.substring(span.start, span.end))
    }

    @Test
    fun noMarkers_isPlainText() {
        val result = "Nothing special here.".withBoldMarkers()

        assertEquals("Nothing special here.", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun multipleAndUnclosedMarkersDegradeGracefully() {
        val two = "**A** and **B**".withBoldMarkers()
        assertEquals("A and B", two.text)
        assertEquals(2, two.spanStyles.size)

        // An unclosed marker just bolds the tail — never crashes or eats text.
        val unclosed = "plain **tail".withBoldMarkers()
        assertEquals("plain tail", unclosed.text)
    }
}
