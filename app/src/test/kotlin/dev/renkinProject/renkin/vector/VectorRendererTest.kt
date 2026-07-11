package dev.renkinProject.renkin.vector

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.vector.VectorRenderer.Companion.renderToCanvas
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel-level checks of the custom vector rasteriser (native graphics mode, so draws are
 * real). The transparent-stroke case is the Lawnicons regression: SVG converters emit
 * strokeColor="#00000000" with strokeWidth="1" on filled paths, and the renderer used to
 * draw only that invisible stroke, blanking the whole icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VectorRendererTest {

    private fun render(vector: ImageVector): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        vector.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun squareVector(
        fill: Color?,
        stroke: Color? = null,
        strokeWidth: Float = 0f
    ): ImageVector = ImageVector.Builder("test", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = PathData {
                moveTo(4f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(20f)
                horizontalLineTo(4f)
                close()
            },
            fill = fill?.let { SolidColor(it) },
            stroke = stroke?.let { SolidColor(it) },
            strokeLineWidth = strokeWidth
        )
        .build()

    @Test
    fun filledPathWithTransparentStroke_stillDrawsTheFill() {
        val bitmap = render(squareVector(fill = Color.Red, stroke = Color(0x00000000), strokeWidth = 1f))
        // Centre of the square must carry the fill — this was fully transparent before the fix.
        assertNotEquals(0, bitmap.getPixel(32, 32))
    }

    @Test
    fun plainFilledPath_drawsAsBefore() {
        val bitmap = render(squareVector(fill = Color.Red))
        assertNotEquals(0, bitmap.getPixel(32, 32))
    }

    @Test
    fun strokeOnlyPath_drawsTheOutlineButNotTheInside() {
        val bitmap = render(squareVector(fill = null, stroke = Color.Black, strokeWidth = 2f))
        // The outline sits on the path edge; the centre stays empty.
        assertTrue(bitmap.getPixel(32, 32) == 0)
        var borderHit = false
        for (x in 0 until 64) if (bitmap.getPixel(x, 32) != 0) { borderHit = true; break }
        assertTrue(borderHit)
    }
}
