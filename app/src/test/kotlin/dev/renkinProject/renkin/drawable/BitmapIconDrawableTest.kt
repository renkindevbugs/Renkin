package dev.renkinProject.renkin.drawable

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapIconDrawableTest {

    @Test
    fun browserPreviewDoesNotReplaceTheFullSizeExportBitmap() {
        val exported = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val browserPreview = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val icon = BitmapIconDrawable(
            exported,
            browserPreviewBitmap = browserPreview
        )

        assertSame(exported, icon.toBitmap())
        assertSame(exported, icon.inAppPreviewBitmap())
        assertSame(browserPreview, icon.toBrowserPreviewBitmap())
    }

    @Test
    fun regularBitmapUsesTheSameImageForBrowserPreview() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val icon = BitmapIconDrawable(bitmap)

        assertSame(bitmap, icon.toBitmap())
        assertSame(bitmap, icon.toBrowserPreviewBitmap())
    }
}
