package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.vector.brush.GradientMatrix
import dev.renkinProject.renkin.vector.brush.GradientType
import dev.renkinProject.renkin.vector.brush.VectorGradientBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which paint a Vector-tab path draws with after a Fill/Stroke toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PathEntryPaintTest {
    private val gradient = VectorGradientBrush.of(GradientType.LINEAR, listOf(Color.Red, Color.Blue), listOf(0f, 1f),
        0f, 0f, 10f, 0f, 0f, TileMode.Clamp, GradientMatrix.IDENTITY)!!

    private fun vector(fill: Brush?, stroke: Brush?) = ImageVector.Builder("test", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathData { moveTo(1f, 1f); lineTo(20f, 20f) }, fill = fill, stroke = stroke, strokeLineWidth = 1f)
        .build()

    private fun path(fill: Brush?, stroke: Brush?) = vector(fill, stroke).root.single() as VectorPath

    @Test
    fun reloadedFilledGradientKeepsItsGradientWhenToggledToStroke() {
        // A stored filled path comes back with a transparent stroke slot.
        val reloaded = path(gradient, SolidColor(Color.Transparent))

        val stroked = PathEntry(reloaded, filled = false, baseStroke = 1f).toMutablePath(2f)

        assertEquals(gradient, stroked.stroke)
        assertNull(stroked.fill)
        assertEquals(2f, stroked.strokeLineWidth, 0f)
    }

    @Test
    fun reloadedStrokedColourKeepsItsColourWhenToggledToFill() {
        val filled = PathEntry(path(null, SolidColor(Color.Green)), filled = true, baseStroke = 1f).toMutablePath(1f)

        assertEquals(SolidColor(Color.Green), filled.fill)
        assertNull(filled.stroke)
    }

    @Test
    fun ownPaintWinsAndUnpaintedPathsStayWhite() {
        val both = PathEntry(path(SolidColor(Color.Red), gradient), filled = true, baseStroke = 1f).toMutablePath(1f)
        assertEquals(SolidColor(Color.Red), both.fill)

        val none = PathEntry(path(SolidColor(Color.Transparent), null), filled = false, baseStroke = 1f).toMutablePath(1f)
        assertEquals(SolidColor(Color.White), none.stroke)
    }

    @Test
    fun hasGradientBrushFindsGradientsOnlyWhenPresent() {
        assertTrue(ImageVectorDrawable(vector(gradient, null)).hasGradientBrush())
        assertTrue(ImageVectorDrawable(vector(null, gradient)).hasGradientBrush())
        assertFalse(ImageVectorDrawable(vector(SolidColor(Color.Red), null)).hasGradientBrush())
    }
}
