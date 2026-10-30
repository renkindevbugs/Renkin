package dev.renkinProject.renkin.drawable

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.icon.creator.IconImageEditPipeline
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyIconDensityTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    // A legacy app icon: a PNG in res/drawable, decoded at 160 dpi, whose top-left quarter is red.
    private fun legacyIcon(): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            for (x in 0 until 32) for (y in 0 until 32) setPixel(x, y, Color.RED)
            density = 160
        }
        return BitmapDrawable(resources, bitmap)
    }

    @Test
    fun theWorkingFrameDrawsLikeEveryOtherBitmapAndLeavesTheResourceBitmapAlone() {
        val drawable = legacyIcon()

        val frame = drawable.shrinkIfBiggerThan(512)!!

        assertEquals(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).density, frame.density)
        assertNotSame(drawable.bitmap, frame)
        assertEquals(160, drawable.bitmap.density)
    }

    @Test
    fun aGlobalScaleEnlargesALegacyIconAroundItsCentreInsteadOfZoomingIntoACorner() {
        val frame = legacyIcon().shrinkIfBiggerThan(512)!!
        val options = globalModifierOptions(preferencesOf(GlobalIconScaleKey to 137))

        val scaled = IconImageEditPipeline(resources, options).applyPrimary(BitmapIconDrawable(resources, frame)).toBitmap()

        // Scaled around the centre, the far corner keeps the blue artwork; a density-scaled draw
        // filled the whole frame with the red top-left quarter.
        assertEquals(Color.BLUE, scaled.getPixel(scaled.width - 2, scaled.height - 2))
        assertEquals(Color.RED, scaled.getPixel(1, 1))
    }
}
