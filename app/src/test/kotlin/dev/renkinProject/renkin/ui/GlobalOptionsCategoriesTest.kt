package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalOptionsCategoriesTest {

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

    private fun app(
        packageName: String,
        hasIcon: Boolean,
        isCustom: Boolean = false,
        isRefreshMade: Boolean = false
    ) = PackageInfoStruct(
        appName = packageName,
        packageName = packageName,
        activityName = "$packageName.Main",
        icon = ColorDrawable(0),
        iconID = 0,
        createdIcon = if (hasIcon) FakeIcon() else null,
        isCustom = isCustom,
        isRefreshMade = isRefreshMade
    )

    @Test
    fun categorization_preservesOrderAndExcludesLockedIconlessApps() {
        val generated = app("generated", hasIcon = true, isRefreshMade = true)
        val custom = app("custom", hasIcon = true, isCustom = true, isRefreshMade = true)
        val existing = app("existing", hasIcon = true)
        val iconless = app("iconless", hasIcon = false)
        val locked = app("locked", hasIcon = false)

        val result = categorizeGlobalIcons(
            listOf(generated, custom, existing, iconless, locked),
            lockedKeys = setOf(locked.key)
        )

        assertEquals(listOf(generated), result.generated)
        assertEquals(listOf(custom), result.custom)
        assertEquals(listOf(existing), result.existing)
        assertEquals(listOf(iconless), result.iconless)
    }
}
