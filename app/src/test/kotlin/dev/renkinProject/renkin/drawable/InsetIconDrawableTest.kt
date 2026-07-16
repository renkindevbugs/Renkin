package dev.renkinProject.renkin.drawable

import android.app.Application
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.icon.creator.IconOutline
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.extension.contentBounds
import dev.renkinProject.renkin.vector.VectorEditor.Companion.inset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsetIconDrawableTest {

    @Test
    fun lawniconsStyleInsetVector_survivesOutlineRasterization() {
        val icon = InsetIconDrawable(
            ImageVectorDrawable(lawniconsVector()),
            Rect(),
            RectF(LAWNICONS_INSET, LAWNICONS_INSET, LAWNICONS_INSET, LAWNICONS_INSET)
        )

        val raster = icon.toBitmap()
        val outlined = IconOutline.apply(raster, OutlineMode.ADD, 8f, AndroidColor.RED)
        val content = requireNotNull(raster.contentBounds())

        assertEquals(256, raster.width)
        assertEquals(256, raster.height)
        assertTrue(content.width() in 110..118)
        assertTrue(AndroidColor.alpha(raster.getPixel(128, 128)) > 0)
        assertTrue(AndroidColor.alpha(outlined.getPixel(128, 128)) > 0)
        assertTrue(hasRedPixel(outlined))
        val outlinedContent = nonOutlineBounds(outlined)
        assertTrue(abs(content.left - outlinedContent.left) <= 1)
        assertTrue(abs(content.top - outlinedContent.top) <= 1)
        assertTrue(abs(content.right - outlinedContent.right) <= 1)
        assertTrue(abs(content.bottom - outlinedContent.bottom) <= 1)
    }

    @Test
    fun lawniconsInset_usesTheRemainingAreaInVectorPreview() {
        val vector = ImageVectorDrawable(lawniconsVector())

        vector.inset(RectF(LAWNICONS_INSET, LAWNICONS_INSET, LAWNICONS_INSET, LAWNICONS_INSET))

        val path = vector.toImageVector().root.first() as VectorPath
        val bounds = path.pathData.toPath().getBounds()
        assertTrue(bounds.width in 10.5f..10.8f)
        assertTrue(bounds.height in 10.5f..10.8f)
    }

    private fun hasRedPixel(bitmap: android.graphics.Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any {
            AndroidColor.alpha(it) > 0 && AndroidColor.red(it) > 200 && AndroidColor.green(it) < 80
        }
    }

    private fun nonOutlineBounds(bitmap: android.graphics.Bitmap): Rect {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = pixels[y * bitmap.width + x]
            if (AndroidColor.alpha(color) == 0 || AndroidColor.red(color) > 100) continue
            left = minOf(left, x); top = minOf(top, y)
            right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun lawniconsVector(): ImageVector = ImageVector.Builder(
        "lawnicons", 24.dp, 24.dp, 24f, 24f
    ).addPath(
        pathData = PathData {
            moveTo(4f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            close()
        },
        fill = SolidColor(Color.Black),
        stroke = SolidColor(Color.Transparent),
        strokeLineWidth = 1f
    ).build()

    companion object {
        private const val LAWNICONS_INSET = 1f / 6f
    }
}
