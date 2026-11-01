package dev.renkinProject.renkin.feature.help

import org.junit.Assert.assertEquals
import org.junit.Test

class IntroShowcaseTest {

    @Test
    fun cardIsCenteredWhenNothingIsHighlighted() {
        assertEquals(
            410,
            introCardTop(emptyList(), cardHeight = 200, safeTop = 20, safeBottom = 1000)
        )
    }

    @Test
    fun cardUsesSpaceBelowTargetNearTop() {
        assertEquals(
            250,
            introCardTop(listOf(100..250), cardHeight = 200, safeTop = 20, safeBottom = 1000)
        )
    }

    @Test
    fun cardUsesSpaceAboveTargetNearBottom() {
        assertEquals(
            500,
            introCardTop(listOf(700..850), cardHeight = 200, safeTop = 20, safeBottom = 1000)
        )
    }

    @Test
    fun cardFitsBetweenSeparatedTargets() {
        assertEquals(
            150,
            introCardTop(
                blocked = listOf(50..150, 700..800),
                cardHeight = 200,
                safeTop = 20,
                safeBottom = 1000
            )
        )
    }
}
