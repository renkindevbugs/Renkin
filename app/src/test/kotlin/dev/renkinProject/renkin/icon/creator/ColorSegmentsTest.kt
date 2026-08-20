package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The segment picker's maths: clustering an icon into colour regions, matching pixels back to a
 * pick, and applying the layer stack in order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorSegmentsTest {

    /** Half red, half blue, with a transparent strip that must be ignored entirely. */
    private fun twoToneIcon(width: Int = 40, height: Int = 20): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = when {
                        y == 0 -> Color.TRANSPARENT
                        x < width / 2 -> Color.RED
                        else -> Color.BLUE
                    }
                    setPixel(x, y, color)
                }
            }
        }

    @Test
    fun segmentColorsFindsTheIconsOwnColours() {
        val segments = segmentColors(twoToneIcon(), count = 2)

        assertEquals(2, segments.size)
        assertTrue(segments.any { Color.red(it.color) > 200 && Color.blue(it.color) < 60 })
        assertTrue(segments.any { Color.blue(it.color) > 200 && Color.red(it.color) < 60 })
        // Both halves are the same size, and the transparent row must not count towards either.
        segments.forEach { assertEquals(0.5f, it.coverage, 0.05f) }
    }

    @Test
    fun segmentColorsIgnoresFullyTransparentIcons() {
        val blank = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        assertTrue(segmentColors(blank).isEmpty())
    }

    @Test
    fun duplicateRoundedClusterColorsBecomeOnePickerSegment() {
        val duplicate = Color.rgb(127, 187, 179)

        val merged = mergeSegmentsByColor(
            listOf(
                ColorSegment(duplicate, 0.35f),
                ColorSegment(Color.BLUE, 0.2f),
                ColorSegment(duplicate, 0.45f)
            )
        )

        assertEquals(listOf(duplicate, Color.BLUE), merged.map(ColorSegment::color))
        assertEquals(0.8f, merged.first().coverage, 0.0001f)
    }

    @Test
    fun matchesSegmentHonoursTolerance() {
        val nearlyRed = Color.rgb(230, 20, 20)

        assertTrue(matchesSegment(nearlyRed, listOf(Color.RED), tolerance = 0.2f))
        assertTrue(!matchesSegment(nearlyRed, listOf(Color.BLUE), tolerance = 0.2f))
        // An empty pick means "everything", which is how the whole-icon path stays a no-op.
        assertTrue(matchesSegment(Color.GREEN, emptyList(), tolerance = 0f))
    }

    @Test
    fun mergeSegmentColorizeKeepsUnpickedPixels() {
        val source = twoToneIcon()
        val colorized = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.GREEN) }

        val merged = mergeSegmentColorize(source, colorized, listOf(Color.RED), tolerance = 0.1f)

        assertEquals(Color.GREEN, merged.getPixel(0, 5))
        assertEquals(Color.BLUE, merged.getPixel(source.width - 1, 5))
    }

    @Test
    fun segmentBoundsCoversOnlyThePickedRegion() {
        val source = twoToneIcon()

        val bounds = segmentBounds(source, listOf(Color.BLUE), tolerance = 0.1f)

        assertNotNull(bounds)
        assertEquals(source.width / 2, bounds!!.left)
        assertEquals(source.width, bounds.right)
        assertNull(segmentBounds(source, listOf(Color.GREEN), tolerance = 0.01f))
    }

    @Test
    fun layersPaintTheirOwnRegionsAndLeaveEarlierOnesAlone() {
        val source = twoToneIcon()
        val layers = listOf(
            SegmentLayer(
                targets = listOf(Color.RED),
                tolerance = 0.1f,
                style = ColorizerStyle(firstColor = Color.GREEN, flat = true)
            ),
            SegmentLayer(
                targets = listOf(Color.BLUE),
                tolerance = 0.1f,
                style = ColorizerStyle(firstColor = Color.WHITE, flat = true)
            )
        )

        val result = applySegmentLayers(source, layers)

        // The second layer matches the icon the first one produced. Blue is still there (the
        // first layer only repainted the red half), so each layer keeps its own region.
        assertEquals(Color.GREEN, result.getPixel(0, 5))
        assertEquals(Color.WHITE, result.getPixel(source.width - 1, 5))
        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
    }

    @Test
    fun layersWithoutRegionsAreSkipped() {
        val source = twoToneIcon()
        val layers = listOf(
            SegmentLayer(
                targets = emptyList(),
                style = ColorizerStyle(firstColor = Color.GREEN, flat = true)
            )
        )

        val result = applySegmentLayers(source, layers)

        assertEquals(Color.RED, result.getPixel(0, 5))
        assertEquals(Color.BLUE, result.getPixel(source.width - 1, 5))
    }

    @Test
    fun segmentLayersSurviveEncoding() {
        val layer = SegmentLayer(
            targets = listOf(Color.RED, Color.BLUE),
            tolerance = 0.27f,
            style = ColorizerStyle(
                mode = ColorizerMode.GRADIENT,
                gradientType = GradientType.RADIAL,
                firstColor = Color.MAGENTA,
                gradientStops = listOf(Color.CYAN, Color.YELLOW),
                gradientAngle = 135f,
                monochrome = true
            )
        )

        val restored = decodeSegmentLayer(layer.encode())

        assertEquals(layer, restored)
        assertNull(decodeSegmentLayer("not-a-layer"))
    }
}
