package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewVariantsTest {

    private fun variants(labels: List<String>, selected: Int?, draftIndex: Int?) = PreviewVariants(
        kind = RotatingIconKind.CALENDAR,
        labels = labels,
        selected = selected,
        draftIndex = draftIndex,
        renderKey = Unit,
        onSelect = {},
        render = { null }
    )

    private val days = (1..31).map(Int::toString)

    @Test
    fun anotherDay_isShownInsteadOfTheDraft() {
        assertEquals(13, variants(days, selected = 13, draftIndex = 30).shownVariant)
    }

    @Test
    fun theDraftsOwnDay_showsTheDraftItself() {
        assertNull(variants(days, selected = 30, draftIndex = 30).shownVariant)
        assertNull(variants(days, selected = null, draftIndex = 30).shownVariant)
    }

    @Test
    fun aNoteWithoutVariants_neverReplacesTheDraft() {
        assertNull(variants(emptyList(), selected = 2, draftIndex = null).shownVariant)
    }
}
