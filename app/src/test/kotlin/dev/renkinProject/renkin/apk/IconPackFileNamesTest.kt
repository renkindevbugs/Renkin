package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards [IconPackBuilder.uniqueDrawableFileNames]: every launcher activity must get its own
 * drawable file name. A regression here is silent — two entries writing the same resource name
 * means one icon simply overwrites the other in the built pack (no crash, no error).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconPackFileNamesTest {

    private fun app(pkg: String, activity: String) =
        PackageInfoStruct(pkg, pkg, activity, ColorDrawable(Color.RED), 0)

    @Test
    fun distinctPackages_useDottedNameAsUnderscores() {
        val a = app("com.foo", "com.foo.Main")
        val b = app("net.bar", "net.bar.Home")

        val names = IconPackBuilder.uniqueDrawableFileNames(listOf(a, b))

        assertEquals("com_foo", names[a.key])
        assertEquals("net_bar", names[b.key])
    }

    @Test
    fun samePackageTwoActivities_getDistinctNames() {
        val a = app("com.foo", "com.foo.Main")
        val b = app("com.foo", "com.foo.Settings")

        val names = IconPackBuilder.uniqueDrawableFileNames(listOf(a, b))

        // Both keys are present and map to different file names — the whole point.
        assertNotEquals(names[a.key], names[b.key])
        assertEquals(2, names.values.toSet().size)
        // First keeps the plain package name; the duplicate is suffixed with its activity class.
        assertEquals("com_foo", names[a.key])
        assertEquals("com_foo_settings", names[b.key])
    }

    @Test
    fun duplicateActivityClassSuffix_getsAltSuffix() {
        // Two different activities whose class names both sanitise to "alpha" — the second
        // collision must fall through to the "_alt" branch instead of reusing the name.
        val first = app("com.foo", "com.foo.Main")
        val a = app("com.foo", "one.two.Alpha")
        val b = app("com.foo", "three.four.Alpha")

        val names = IconPackBuilder.uniqueDrawableFileNames(listOf(first, a, b))

        assertEquals("com_foo", names[first.key])
        assertEquals("com_foo_alpha", names[a.key])
        assertEquals("com_foo_alpha_alt", names[b.key])
        assertEquals(3, names.values.toSet().size)
    }

    @Test
    fun activityWithoutDotOrSymbols_stillProducesUsableSuffix() {
        val first = app("com.foo", "com.foo.Main")
        // Activity that ends on a dot (empty class segment) falls back to the "alt" suffix.
        val trailingDot = app("com.foo", "com.foo.")
        // Non-alphanumeric characters are stripped from the suffix.
        val symbols = app("com.foo", "com.foo.Se\$ttings#2")

        val names = IconPackBuilder.uniqueDrawableFileNames(listOf(first, trailingDot, symbols))

        assertEquals("com_foo_alt", names[trailingDot.key])
        assertEquals("com_foo_settings2", names[symbols.key])
        assertTrue(names.values.all { it.isNotEmpty() })
        assertEquals(3, names.values.toSet().size)
    }
}
