package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards [IconPackBuilder.ambiguousActivityClasses]: appmap.xml maps icons by class name alone, so a
 * class used by two packages would give one app's icon to the other (profile packs, Chrome/Brave).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppMapAmbiguousClassesTest {

    private fun app(pkg: String, activity: String) =
        PackageInfoStruct(pkg, pkg, activity, ColorDrawable(Color.RED), 0)

    private val packActivity = "dev.renkinProject.renkinpack.MainActivity"

    @Test
    fun classSharedByTwoPackagesIsAmbiguous() {
        val ambiguous = IconPackBuilder.ambiguousActivityClasses(listOf(
            app("com.android.chrome", "com.google.android.apps.chrome.Main"),
            app("com.brave.browser", "com.google.android.apps.chrome.Main"),
            app("com.foo", "com.foo.Main")
        ))

        assertEquals(setOf("com.google.android.apps.chrome.Main", packActivity), ambiguous)
    }

    @Test
    fun severalActivitiesOfOnePackageStayListed() {
        val ambiguous = IconPackBuilder.ambiguousActivityClasses(listOf(
            app("com.foo", "com.foo.Main"),
            app("com.foo", "com.foo.Main")
        ))

        assertEquals(setOf(packActivity), ambiguous)
    }

    @Test
    fun profilePackActivityIsAmbiguousEvenWhenOnlyOnePackIsInstalled() {
        val ambiguous = IconPackBuilder.ambiguousActivityClasses(listOf(app("dev.renkinProject.renkinpack.p26", packActivity)))

        assertEquals(setOf(packActivity), ambiguous)
    }
}
