package dev.renkinProject.renkin.packages

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.drawable.IconPackDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackageInfoStructTest {

    private class FakeIcon : IconPackDrawable() {
        @Composable override fun getPainter(): Painter = throw UnsupportedOperationException()
        override fun toBitmap(): Bitmap = throw UnsupportedOperationException()
        override fun toDbString(): String = "fake"
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private fun app(icon: IconPackDrawable) = PackageInfoStruct(
        appName = "App",
        packageName = "com.example.app",
        activityName = "com.example.app.Main",
        icon = ColorDrawable(0),
        iconID = 0,
        createdIcon = icon,
        sourcePackName = "pack.source",
        isRefreshMade = true
    )

    @Test
    fun changeExport_nullIcon_removesPackReferenceAndRefreshFlag() {
        val cleared = app(FakeIcon()).changeExport(null)

        assertNull(cleared.createdIcon)
        assertNull(cleared.sourcePackName)
        assertFalse(cleared.isRefreshMade)
    }

    @Test
    fun changeExport_replacementWithoutPack_removesPreviousPackReference() {
        val replacement = FakeIcon()
        val changed = app(FakeIcon()).changeExport(replacement, sourcePackName = null)

        assertSame(replacement, changed.createdIcon)
        assertNull(changed.sourcePackName)
    }

    @Test
    fun key_isStableAndComputedOnce() {
        val application = app(FakeIcon())
        val firstRead = application.key

        assertEquals("com.example.app/com.example.app.Main", firstRead)
        assertSame(firstRead, application.key)
    }
}
