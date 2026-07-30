package dev.renkinProject.renkin

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WallpaperWindowTest {

    @Test
    fun wallpaperWindow_showsWallpaperWithoutDimmingTransparentContent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        activity.window.showWallpaperBehindContent()

        val flags = activity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val background = activity.window.decorView.background as ColorDrawable
        assertEquals(Color.TRANSPARENT, background.color)
    }
}
