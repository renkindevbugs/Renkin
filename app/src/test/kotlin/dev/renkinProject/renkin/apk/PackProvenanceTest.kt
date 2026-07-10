package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provenance asset built into every Renkin pack: only genuine foreign-pack sources are
 * recorded, and a round trip through encode/parse preserves the component→pack mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackProvenanceTest {

    private fun app(pkg: String, source: String?, withIcon: Boolean = true) = PackageInfoStruct(
        appName = pkg,
        packageName = pkg,
        activityName = "$pkg.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = if (withIcon) {
            BitmapIconDrawable(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888), false)
        } else null,
        sourcePackName = source
    )

    @Test
    fun encodeParse_roundTripsForeignPackSources() {
        val json = PackProvenance.encode(
            listOf(
                app("com.a", "pack.whicons"),
                app("com.b", "pack.linios")
            )
        )

        val parsed = PackProvenance.parse(json)

        assertEquals(mapOf("com.a/com.a.Main" to "pack.whicons", "com.b/com.b.Main" to "pack.linios"), parsed)
    }

    @Test
    fun encode_skipsOwnSourcesUploadsAndIconlessApps() {
        val json = PackProvenance.encode(
            listOf(
                app("com.upload", null),
                app("com.empty", ""),
                app("com.own", IconPackBuilder.PACKAGE_NAME + ".p3"),
                app("com.noicon", "pack.real", withIcon = false)
            )
        )

        assertTrue(PackProvenance.parse(json).isEmpty())
    }

    @Test
    fun parse_toleratesGarbage() {
        assertTrue(PackProvenance.parse("not json").isEmpty())
    }
}
