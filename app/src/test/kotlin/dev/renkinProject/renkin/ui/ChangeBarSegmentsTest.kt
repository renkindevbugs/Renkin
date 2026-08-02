package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.ui.theme.RemovedRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hero card's change bar: which colour covers which part of the bar, and in what order. */
class ChangeBarSegmentsTest {

    private val built = Color.Blue

    @Test
    fun runsFollowEachOtherWithoutGaps() {
        val segments = changeBarSegments(built = 0.5f, added = 0.2f, removed = 0.1f, builtColor = built)

        assertEquals(3, segments.size)
        assertEquals(0f, segments[0].first, TOLERANCE)
        segments.zipWithNext { current, next ->
            assertEquals(current.second, next.first, TOLERANCE)
        }
        assertEquals(0.8f, segments.last().second, TOLERANCE)
    }

    @Test
    fun additionsArePaintedBeforeRemovals() {
        val segments = changeBarSegments(built = 0.5f, added = 0.2f, removed = 0.1f, builtColor = built)

        assertEquals(built, segments[0].third)
        assertEquals(AddedGreen, segments[1].third)
        assertEquals(RemovedRed, segments[2].third)
    }

    @Test
    fun removalsUseTheirOwnColourNotTheThemeError() {
        // The removed run used to take colorScheme.error, which a warm Material You palette
        // rendered close to the built colour and made the run vanish into it.
        val removedColour = changeBarSegments(
            built = 0.5f, added = 0.2f, removed = 0.1f, builtColor = built
        ).last().third

        assertEquals(RemovedRed, removedColour)
        assertTrue(removedColour != built)
    }

    @Test
    fun emptyRunsAreDropped() {
        val onlyBuilt = changeBarSegments(built = 1f, added = 0f, removed = 0f, builtColor = built)
        assertEquals(1, onlyBuilt.size)
        assertEquals(built, onlyBuilt.single().third)

        val freshProfile = changeBarSegments(built = 0f, added = 0.3f, removed = 0f, builtColor = built)
        assertEquals(1, freshProfile.size)
        assertEquals(AddedGreen, freshProfile.single().third)
        assertEquals(0f, freshProfile.single().first, TOLERANCE)

        assertTrue(changeBarSegments(0f, 0f, 0f, built).isEmpty())
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
