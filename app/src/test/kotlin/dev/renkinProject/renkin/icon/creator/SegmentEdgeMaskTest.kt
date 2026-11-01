package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recoloured and erased segments keep the artwork's anti-aliased edges. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentEdgeMaskTest {
    private val gray = Color.rgb(128, 128, 128)

    /** White | anti-aliased gray column | black, five pixels wide. */
    private fun edgeIcon(): Bitmap = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            setPixel(x, y, when {
                x < 2 -> Color.WHITE
                x == 2 -> gray
                else -> Color.BLACK
            })
        }
    }

    private fun mask(icon: Bitmap): FloatArray {
        val pixels = IntArray(icon.width * icon.height)
        icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)
        return segmentEdgeMask(pixels, icon.width, icon.height, listOf(Color.WHITE), 0.05f)
    }

    @Test fun selectedPixelsCountFullyAndAnEdgeBlendCountsByItsMix() {
        val mask = mask(edgeIcon())
        assertEquals(1f, mask[1], 0f)
        assertEquals(0.5f, mask[2], 0.02f)
        assertEquals(0f, mask[3], 0f)
        assertEquals(0f, mask[4], 0f)
    }

    @Test fun anEdgeBlendingIntoWhiteCountsToo() {
        val icon = edgeIcon()
        val pixels = IntArray(icon.width * icon.height)
        icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)
        // White is 0xFFFFFFFF (-1): it must still count as the outside colour.
        val mask = segmentEdgeMask(pixels, icon.width, icon.height, listOf(Color.BLACK), 0.05f)
        assertEquals(0.5f, mask[2], 0.02f)
        assertEquals(0f, mask[1], 0f)
    }

    @Test fun similarColoursAwayFromTheSelectionStayUntouched() {
        val icon = Bitmap.createBitmap(5, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.WHITE)
            setPixel(1, 0, Color.BLACK)
            setPixel(2, 0, Color.BLACK)
            // Just outside the tolerance, but not next to anything selected.
            setPixel(3, 0, Color.rgb(200, 200, 200))
            setPixel(4, 0, Color.BLACK)
        }
        assertEquals(0f, mask(icon)[3], 0f)
    }

    @Test fun recolouringBlendsTheEdgeInsteadOfLeavingItGray() {
        val source = edgeIcon()
        val layer = SegmentLayer(listOf(Color.WHITE), 0.05f, ColorizerStyle(firstColor = Color.RED, flat = true))
        val result = applySegmentLayers(source, listOf(layer))

        assertEquals(Color.RED, result.getPixel(0, 1))
        assertEquals(Color.BLACK, result.getPixel(4, 1))
        val edge = result.getPixel(2, 1)
        assertTrue("edge must lean toward the new colour", Color.red(edge) > Color.green(edge) + 30)
    }

    @Test fun erasingFadesTheEdgeInsteadOfCuttingIt() {
        val result = removeSegmentColors(edgeIcon(), listOf(Color.WHITE), 0.05f)

        assertEquals(0, Color.alpha(result.getPixel(0, 1)))
        assertEquals(255, Color.alpha(result.getPixel(4, 1)))
        assertEquals(128f, Color.alpha(result.getPixel(2, 1)).toFloat(), 8f)
    }
}
