package dev.renkinProject.renkin.packages

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApplicationManagerComponentTest {

    @Test
    fun launcherActivityIcon_winsOverApplicationFallback() {
        val activityIcon = ColorDrawable(Color.RED)
        val applicationIcon = ColorDrawable(Color.BLUE)

        assertSame(activityIcon, launcherIconOrFallback(activityIcon) { applicationIcon })
        assertEquals(20, launcherIconIdOrFallback(20, 10))
    }

    @Test
    fun missingLauncherActivityIcon_usesApplicationFallback() {
        val applicationIcon = ColorDrawable(Color.BLUE)

        assertSame(applicationIcon, launcherIconOrFallback(null) { applicationIcon })
        assertEquals(10, launcherIconIdOrFallback(0, 10))
        assertEquals(10, launcherIconIdOrFallback(null, 10))
    }

    @Test
    fun materialYouPackQuery_usesTheIconThemeCapabilityCategory() {
        val intent = iconPackQueryIntent(
            materialYouColorsOnly = true,
            packageName = "com.example.pack"
        )

        assertEquals(ICON_PACK_ACTION, intent.action)
        assertEquals("com.example.pack", intent.`package`)
        assertTrue(intent.categories.orEmpty().contains(CHANGES_WITH_MATERIAL_YOU_COLORS))
    }

    @Test
    fun ordinaryPackQuery_doesNotRequireMaterialYouCapability() {
        val intent = iconPackQueryIntent()

        assertEquals(ICON_PACK_ACTION, intent.action)
        assertFalse(intent.categories.orEmpty().contains(CHANGES_WITH_MATERIAL_YOU_COLORS))
    }
}
