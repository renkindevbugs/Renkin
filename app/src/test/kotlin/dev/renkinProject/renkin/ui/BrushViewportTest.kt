package dev.renkinProject.renkin.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushViewportTest {

    @Test
    fun zoomMakesTheStoredBrushFinerWhileKeepingItsScreenSize() {
        val viewport = BrushViewport(zoom = 4f)

        assertEquals(0.025f, viewport.contentBrush(0.1f), 0.0001f)
    }

    @Test
    fun offCentreZoomKeepsTheContentBelowTheFocalPoint() {
        val focalPoint = Offset(75f, 50f)
        val viewport = BrushViewport().transformed(
            zoomChange = 2f,
            previousCentroid = focalPoint,
            currentCentroid = focalPoint,
            size = IntSize(100, 100)
        )

        assertEquals(0.75f, viewport.contentPosition(focalPoint, IntSize(100, 100)).x, 0.0001f)
        assertEquals(0.5f, viewport.contentPosition(focalPoint, IntSize(100, 100)).y, 0.0001f)
    }

    @Test
    fun zoomedContentCannotBePannedOutsideTheViewport() {
        val viewport = BrushViewport().transformed(
            zoomChange = 2f,
            previousCentroid = Offset(50f, 50f),
            currentCentroid = Offset(500f, 500f),
            size = IntSize(100, 100)
        )

        assertEquals(Offset(50f, 50f), viewport.pan)
    }
}
