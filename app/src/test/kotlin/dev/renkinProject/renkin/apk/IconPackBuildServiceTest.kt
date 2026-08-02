package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconPackBuildServiceTest {

    private fun app(name: String, sourcePack: String) = PackageInfoStruct(
        appName = name,
        packageName = "com.$name",
        activityName = "com.$name.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = BitmapIconDrawable(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            false
        ),
        sourcePackName = sourcePack
    )

    @Test
    fun noLockedSources_keepsOriginalSnapshot() {
        val apps = listOf(app("mail", "pack.free"))

        val result = excludeLockedSources(apps, emptySet())

        assertSame(apps, result)
    }

    @Test
    fun lockedSource_isExcludedWithoutChangingOtherBuildEntries() {
        val locked = app("mail", "pack.locked")
        val available = app("calendar", "pack.available")

        val result = excludeLockedSources(
            profileApps = listOf(locked, available),
            lockedSources = setOf("pack.locked")
        )

        assertNull(result[0].createdIcon)
        assertNull(result[0].sourcePackName)
        assertSame(available, result[1])
    }
}
