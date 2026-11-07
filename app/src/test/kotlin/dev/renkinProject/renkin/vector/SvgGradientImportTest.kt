package dev.renkinProject.renkin.vector

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import dev.renkinProject.renkin.vector.brush.GradientMatrix
import dev.renkinProject.renkin.vector.brush.GradientType
import dev.renkinProject.renkin.vector.brush.VectorGradientBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** `url(#id)` paints resolved by the SVG import; anything not drawable exactly still rejects the file. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgGradientImportTest {

    private fun svg(body: String) = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 24 24">$body</svg>"""

    private fun gradientOf(body: String, index: Int = 0): VectorGradientBrush =
        SvgVectorImporter.parse(svg(body))!!.paths[index].gradient as VectorGradientBrush

    private val square = """<path fill="url(#g)" d="M4 4h16v16H4Z"/>"""

    @Test
    fun userSpaceLinearGradientDeclaredAfterThePath() {
        val brush = gradientOf(square + """
            <defs><linearGradient id="g" x1="1" y1="2" x2="23" y2="22" gradientUnits="userSpaceOnUse">
              <stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/>
            </linearGradient></defs>""")

        assertEquals(GradientType.LINEAR, brush.type)
        assertEquals(listOf(1f, 2f, 23f, 22f), listOf(brush.x0, brush.y0, brush.x1, brush.y1))
        assertEquals(GradientMatrix.IDENTITY, brush.matrix)
        assertEquals(listOf(Color.Red, Color.Blue), brush.colors)
    }

    @Test
    fun objectBoundingBoxMapsUnitSpaceOntoThePathBounds() {
        val brush = gradientOf("""
            <linearGradient id="g"><stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/></linearGradient>
            $square""")

        assertEquals(listOf(0f, 0f, 1f, 0f), listOf(brush.x0, brush.y0, brush.x1, brush.y1))
        val m = brush.matrix
        assertEquals(16f, m.a, 0.05f); assertEquals(16f, m.d, 0.05f)
        assertEquals(4f, m.e, 0.05f); assertEquals(4f, m.f, 0.05f)
    }

    @Test
    fun hrefChainInheritsStopsAndOverridesAttributes() {
        val brush = gradientOf("""
            <linearGradient id="base" spreadMethod="reflect">
              <stop offset="10%" stop-color="#f00"/><stop offset="90%" stop-color="#00f"/>
            </linearGradient>
            <radialGradient id="g" xlink:href="#base" cx="12" cy="12" r="8" gradientUnits="userSpaceOnUse"/>
            $square""")

        assertEquals(GradientType.RADIAL, brush.type)
        assertEquals(8f, brush.radius, 0f)
        assertEquals(listOf(0.1f, 0.9f), brush.offsets)
        assertEquals(TileMode.Mirror, brush.tileMode)
    }

    @Test
    fun stopAndPathOpacityFoldIntoTheStops() {
        val imported = SvgVectorImporter.parse(svg("""
            <linearGradient id="g"><stop offset="0" stop-color="#f00" stop-opacity="0.5"/><stop offset="1" style="stop-color:#00f"/></linearGradient>
            <path fill="url(#g)" fill-opacity="0.5" d="M4 4h16v16H4Z"/>"""))!!
        val path = imported.paths.single()
        val brush = path.gradient as VectorGradientBrush

        assertEquals(1f, path.alpha, 0f)
        assertEquals(0.25f, brush.colors[0].alpha, 0.01f)
        assertEquals(0.5f, brush.colors[1].alpha, 0.01f)
        assertEquals(1f, brush.colors[1].blue, 0.01f)
    }

    @Test
    fun gradientTransformRotatesTheGradient() {
        val brush = gradientOf("""
            <linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0" gradientTransform="rotate(90 12 12)">
              <stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/>
            </linearGradient>$square""")

        assertEquals(12f, brush.matrix.mapX(22f, 12f), 1e-3f)
        assertEquals(22f, brush.matrix.mapY(22f, 12f), 1e-3f)
    }

    @Test
    fun strokeGradientOnADualPaintPathLandsOnTheStrokeHalf() {
        val paths = SvgVectorImporter.parse(svg("""
            <linearGradient id="g"><stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/></linearGradient>
            <path fill="#00ff00" stroke="url(#g)" stroke-width="2" d="M4 4h16v16H4Z"/>"""))!!.paths

        assertEquals(2, paths.size)
        assertTrue(paths[0].filled)
        assertNull(paths[0].gradient)
        assertTrue(paths[1].gradient is VectorGradientBrush)
    }

    @Test
    fun singleStopGradientImportsAsItsSolidColour() {
        val path = SvgVectorImporter.parse(svg("""
            <linearGradient id="g"><stop offset="0.3" stop-color="#00f"/></linearGradient>$square"""))!!.paths.single()

        assertEquals(SolidColor(Color.Blue), path.gradient)
    }

    @Test
    fun gradientsThatCannotBeDrawnExactlyStillRejectTheFile() {
        val stops = """<stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/>"""
        assertNull(SvgVectorImporter.parse(svg("""<radialGradient id="g" fx="0.2">$stops</radialGradient>$square""")))
        assertNull(SvgVectorImporter.parse(svg("""<linearGradient id="g"><stop offset="0" stop-color="currentColor"/><stop offset="1" stop-color="#00f"/></linearGradient>$square""")))
        assertNull(SvgVectorImporter.parse(svg("""<linearGradient id="g" xlink:href="#missing"/>$square""")))
        assertNull(SvgVectorImporter.parse(svg("""<defs><path id="p" d="M0 0h1"/><linearGradient id="g">$stops</linearGradient></defs>$square""")))
        assertNull(SvgVectorImporter.parse(svg("""<pattern id="g"/>$square""")))
    }
}
