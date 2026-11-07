package dev.renkinProject.renkin.vector.brush

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorGradientBrushTest {

    private fun linear(
        colors: List<Color> = listOf(Color.Red, Color.Blue),
        offsets: List<Float> = listOf(0f, 1f),
        x1: Float = 10f,
        matrix: GradientMatrix = GradientMatrix.IDENTITY
    ) = VectorGradientBrush.of(GradientType.LINEAR, colors, offsets, 0f, 0f, x1, 0f, 0f, TileMode.Clamp, matrix)

    @Test
    fun equalValuesAreEqualBrushes() {
        assertEquals(linear(), linear())
        assertEquals(linear().hashCode(), linear().hashCode())
    }

    @Test
    fun degenerateGradientsPaintTheirLastColour() {
        assertEquals(SolidColor(Color.Blue), linear(listOf(Color.Blue), listOf(0.5f)))
        assertEquals(SolidColor(Color.Blue), linear(x1 = 0f))
        val zeroRadius = VectorGradientBrush.of(GradientType.RADIAL, listOf(Color.Red, Color.Blue), listOf(0f, 1f),
            5f, 5f, 0f, 0f, 0f, TileMode.Clamp, GradientMatrix.IDENTITY)
        assertEquals(SolidColor(Color.Blue), zeroRadius)
    }

    @Test
    fun invalidValuesAreRejected() {
        assertNull(linear(offsets = listOf(0.8f, 0.2f)))
        assertNull(linear(offsets = listOf(0f, 1.5f)))
        assertNull(linear(offsets = listOf(0f)))
        assertNull(linear(offsets = listOf(0f, Float.NaN)))
        assertNull(linear(matrix = GradientMatrix(0f, 0f, 0f, 0f, 0f, 0f)))
        assertNull(linear(colors = List(VectorGradientBrush.MAX_STOPS + 1) { Color.Red },
            offsets = List(VectorGradientBrush.MAX_STOPS + 1) { 0f }))
    }

    @Test
    fun transformComposesOutsideTheStoredMatrix() {
        val brush = linear(matrix = GradientMatrix.scale(2f, 2f)) as VectorGradientBrush
        val moved = brush.transformed(GradientMatrix.translate(3f, 4f))
        assertEquals(GradientMatrix(2f, 0f, 0f, 2f, 3f, 4f), moved.matrix)
        assertEquals(23f, moved.matrix.mapX(10f, 0f), 0f)
    }

    @Test
    fun pivotedScaleAndRotateKeepThePivotFixed() {
        val scale = GradientMatrix.scale(0.5f, 0.5f, 12f, 12f)
        assertEquals(12f, scale.mapX(12f, 12f), 1e-5f)
        assertEquals(12f, scale.mapY(12f, 12f), 1e-5f)
        assertEquals(10f, scale.mapX(8f, 8f), 1e-5f)
        val rotate = GradientMatrix.rotate(90f, 12f, 12f)
        assertEquals(12f, rotate.mapX(12f, 12f), 1e-4f)
        assertEquals(12f, rotate.mapX(22f, 12f), 1e-4f)
        assertEquals(22f, rotate.mapY(22f, 12f), 1e-4f)
    }

    @Test
    fun fallbackColourIsTheOffsetWeightedMean() {
        val even = linear() as VectorGradientBrush
        val mean = even.fallbackColor()
        assertEquals(0.5f, mean.red, 0.01f)
        assertEquals(0.5f, mean.blue, 0.01f)
        assertEquals(even.fallbackColor(), (linear() as VectorGradientBrush).fallbackColor())

        val mostlyBlue = linear(offsets = listOf(0f, 0.1f)) as VectorGradientBrush
        assertTrue(mostlyBlue.fallbackColor().blue > 0.9f)
    }

    @Test
    fun transformedByLeavesSolidBrushesAlone() {
        val solid = SolidColor(Color.Green)
        assertEquals(solid, solid.transformedBy(GradientMatrix.translate(1f, 1f)))
        assertNull(null.transformedBy(GradientMatrix.translate(1f, 1f)))
    }
}
