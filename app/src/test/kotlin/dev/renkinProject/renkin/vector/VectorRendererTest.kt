package dev.renkinProject.renkin.vector

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.vector.VectorRenderer.Companion.renderToCanvas
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import org.junit.Assert.assertEquals
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

    @Test
    fun referenceBrush_rendersItsFallbackColor() {
        val vector = ImageVector.Builder("reference", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathData {
                    moveTo(4f, 4f)
                    horizontalLineTo(20f)
                    verticalLineTo(20f)
                    horizontalLineTo(4f)
                    close()
                },
                fill = ReferenceBrush("@color/icon_color", Color.Blue)
            )
            .build()

        val bitmap = render(vector)

        assertNotEquals(0, bitmap.getPixel(32, 32))
    }

    private fun groupedRectangle(
        rect: RectF, scale: Float, rotation: Float, pivotX: Float, pivotY: Float, translationX: Float = 0f
    ): ImageVector = ImageVector.Builder("group", 108.dp, 108.dp, 108f, 108f)
        .addGroup(
            rotate = rotation, pivotX = pivotX, pivotY = pivotY,
            scaleX = scale, scaleY = scale, translationX = translationX
        )
        .addPath(
            pathData = PathData {
                moveTo(rect.left, rect.top)
                horizontalLineTo(rect.right)
                verticalLineTo(rect.bottom)
                horizontalLineTo(rect.left)
                close()
            },
            fill = SolidColor(Color.Red)
        )
        .clearGroup()
        .build()

    /** Where android.graphics.drawable.VectorDrawable puts the rectangle (VGroup local matrix order). */
    private fun frameworkBounds(
        rect: RectF, scale: Float, rotation: Float, pivotX: Float, pivotY: Float, translationX: Float = 0f
    ): Rect {
        val matrix = Matrix().apply {
            postTranslate(-pivotX, -pivotY)
            postScale(scale, scale)
            postRotate(rotation, 0f, 0f)
            postTranslate(translationX + pivotX, pivotY)
        }
        val mapped = RectF(rect).also(matrix::mapRect)
        return Rect(Math.round(mapped.left), Math.round(mapped.top), Math.round(mapped.right), Math.round(mapped.bottom))
    }

    private fun opaqueBounds(bitmap: Bitmap): Rect {
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) ushr 24 == 0) continue
            bounds.union(x, y, x + 1, y + 1)
        }
        return bounds
    }

    private fun assertBothPathsMatchTheFramework(
        rect: RectF, scale: Float, rotation: Float, pivotX: Float, pivotY: Float, translationX: Float = 0f
    ) {
        val vector = groupedRectangle(rect, scale, rotation, pivotX, pivotY, translationX)
        val expected = frameworkBounds(rect, scale, rotation, pivotX, pivotY, translationX)
        for (nonScalingStroke in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            vector.renderToCanvas(Canvas(bitmap), nonScalingStroke = nonScalingStroke)
            val actual = opaqueBounds(bitmap)
            val message = "nonScalingStroke=$nonScalingStroke expected $expected but was $actual"
            assertEquals(message, expected.left.toFloat(), actual.left.toFloat(), 1f)
            assertEquals(message, expected.top.toFloat(), actual.top.toFloat(), 1f)
            assertEquals(message, expected.right.toFloat(), actual.right.toFloat(), 1f)
            assertEquals(message, expected.bottom.toFloat(), actual.bottom.toFloat(), 1f)
        }
    }

    // Pack vectors may scale a group around a pivot; both render paths must place it where the
    // framework does. The scaling path once scaled around the negated pivot.
    @Test
    fun scaledGroupWithPivot_matchesTheFrameworkPlacement() {
        assertBothPathsMatchTheFramework(RectF(30f, 30f, 78f, 78f), scale = 0.5f, rotation = 0f, pivotX = 54f, pivotY = 54f)
    }

    @Test
    fun rotatedScaledTranslatedGroupWithPivot_matchesTheFrameworkPlacement() {
        assertBothPathsMatchTheFramework(
            RectF(30f, 40f, 78f, 50f), scale = 0.75f, rotation = 90f, pivotX = 40f, pivotY = 60f, translationX = 6f
        )
    }
}
