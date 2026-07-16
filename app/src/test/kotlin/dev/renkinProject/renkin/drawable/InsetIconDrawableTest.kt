package dev.renkinProject.renkin.drawable

import android.app.Application
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.icon.creator.IconOutline
import dev.renkinProject.renkin.icon.creator.OutlineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsetIconDrawableTest {

    @Test
    fun lawniconsStyleInsetVector_survivesOutlineRasterization() {
        val vector = ImageVector.Builder("lawnicons", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathData {
                    moveTo(4f, 4f)
                    horizontalLineTo(20f)
                    verticalLineTo(20f)
                    horizontalLineTo(4f)
                    close()
                },
                fill = SolidColor(Color.Black),
                // Lawnicons-style converted vectors can carry an invisible stroke.
                stroke = SolidColor(Color.Transparent),
                strokeLineWidth = 1f
            )
            .build()
        val icon = InsetIconDrawable(
            ImageVectorDrawable(vector),
            Rect(),
            RectF(0.1f, 0.1f, 0.1f, 0.1f)
        )

        val raster = icon.toBitmap()
        val outlined = IconOutline.apply(raster, OutlineMode.ADD, 8f, AndroidColor.RED)

        assertEquals(256, raster.width)
        assertEquals(256, raster.height)
        assertTrue(AndroidColor.alpha(raster.getPixel(128, 128)) > 0)
        assertTrue(AndroidColor.alpha(outlined.getPixel(128, 128)) > 0)
        assertTrue(hasRedPixel(outlined))
    }

    private fun hasRedPixel(bitmap: android.graphics.Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any {
            AndroidColor.alpha(it) > 0 && AndroidColor.red(it) > 200 && AndroidColor.green(it) < 80
        }
    }
}
