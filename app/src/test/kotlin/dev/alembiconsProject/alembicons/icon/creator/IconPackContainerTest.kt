package dev.alembiconsProject.alembicons.icon.creator

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [IconPackContainer] indexes a pack's drawables by package name for O(1) lookup. These pin the
 * lookup and the "keep the first entry on a duplicate package" rule a bulk build relies on.
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
        assertEquals(10, container.getApplicationIcon("com.a")?.resourceId)
    }

    @Test
    fun keepsTheFirstEntryOnDuplicatePackageName() {
        // Two activities of the same app — the first inserted wins (find-first behaviour).
        val container = IconPackContainer(
            "pack",
            linkedMapOf(
                InstalledApplication("com.a", "com.a.First", 0) to resource(1),
                InstalledApplication("com.a", "com.a.Second", 0) to resource(2)
            )
        )
        assertEquals(1, container.getApplicationIcon("com.a")?.resourceId)
    }

    @Test
    fun unknownPackageReturnsNull() {
        assertNull(IconPackContainer("pack", emptyMap()).getApplicationIcon("com.missing"))
    }
}
