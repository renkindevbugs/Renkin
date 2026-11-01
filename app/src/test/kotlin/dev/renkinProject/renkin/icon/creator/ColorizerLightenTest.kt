package dev.renkinProject.renkin.icon.creator

import android.app.Application

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ColorizerLightenTest {

    @Test
    fun singleColorLighten_preservesSourceAlphaMask() {
        val source = Bitmap.createBitmap(
            intArrayOf(Color.TRANSPARENT, Color.argb(128, 64, 96, 128)),
            2,
            1,
            Bitmap.Config.ARGB_8888
        )

        val result = colorizeSampleBitmap(
            source,
            ColorizerStyle(firstColor = Color.rgb(255, 128, 0), lighten = true)
        )

        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        assertEquals(Color.argb(128, 255, 175, 128), result.getPixel(1, 0))
    }

    @Test
    fun singleColorInverse_tintsBeforeInvertingLikeTheGenerator() {
        val source = Bitmap.createBitmap(intArrayOf(Color.BLACK), 1, 1, Bitmap.Config.ARGB_8888)

        val result = colorizeSampleBitmap(
            source,
            ColorizerStyle(firstColor = Color.RED, lighten = true, inverse = true)
        )

        assertEquals(Color.CYAN, result.getPixel(0, 0))
    }
}
