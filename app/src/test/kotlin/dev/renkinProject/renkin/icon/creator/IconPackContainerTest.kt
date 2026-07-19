package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.drawable.ResourceDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [IconPackContainer] indexes a pack's drawables by full component for O(1) lookup. Two launcher
 * activities in one package may legitimately have different appfilter mappings.
 * Robolectric because [ResourceDrawable] wraps an android Drawable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconPackContainerTest {

    private fun resource(id: Int) = ResourceDrawable(id, ColorDrawable(Color.RED))

    @Test
    fun looksUpIconByPackageName() {
        val container = IconPackContainer(
            "pack",
            linkedMapOf(InstalledApplication("com.a", "com.a.Main", 0) to resource(10))
        )
        assertEquals(
            10,
            container.getApplicationIcon(InstalledApplication("com.a", "com.a.Main", 99))?.resourceId
        )
    }

    @Test
    fun samePackageActivities_keepTheirOwnComponentMappings() {
        val container = IconPackContainer(
            "pack",
            linkedMapOf(
                InstalledApplication("com.a", "com.a.First", 0) to resource(1),
                InstalledApplication("com.a", "com.a.Second", 0) to resource(2)
            )
        )
        assertEquals(
            1,
            container.getApplicationIcon(InstalledApplication("com.a", "com.a.First", 0))?.resourceId
        )
        assertEquals(
            2,
            container.getApplicationIcon(InstalledApplication("com.a", "com.a.Second", 0))?.resourceId
        )
    }

    @Test
    fun unknownPackageReturnsNull() {
        assertNull(
            IconPackContainer("pack", emptyMap())
                .getApplicationIcon(InstalledApplication("com.missing", "com.missing.Main", 0))
        )
    }
}
