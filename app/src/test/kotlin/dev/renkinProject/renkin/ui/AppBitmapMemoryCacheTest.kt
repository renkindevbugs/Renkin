package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppBitmapMemoryCacheTest {

    private fun key(drawable: ColorDrawable = ColorDrawable(0)) =
        AppBitmapCacheKey("com.app/com.app.Main", 1, drawable, 56)

    @Test
    fun sameLauncherMetadata_isRasterizedOnlyOnce() {
        val cache = AppBitmapMemoryCache(maxSizeBytes = 1024)
        val expected = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cacheKey = key(ColorDrawable(0))
        var loads = 0

        val first = cache.getOrLoad(cacheKey) { loads++; expected }
        val second = cache.getOrLoad(cacheKey) { loads++; Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }

        assertSame(first, second)
        assertEquals(1, loads)
    }

    @Test
    fun freshDrawableIdentity_invalidatesTheCachedBitmap() {
        val cache = AppBitmapMemoryCache(maxSizeBytes = 1024)
        val first = cache.getOrLoad(key(ColorDrawable(0))) { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
        val second = cache.getOrLoad(key(ColorDrawable(0))) { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }

        assertNotSame(first, second)
    }

    @Test
    fun byteLimit_evictsLeastRecentlyUsedBitmap() {
        val cache = AppBitmapMemoryCache(maxSizeBytes = 32)
        val firstKey = key(ColorDrawable(1))
        val secondKey = key(ColorDrawable(2))
        val thirdKey = key(ColorDrawable(3))
        var firstLoads = 0

        cache.getOrLoad(firstKey) { firstLoads++; Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
        cache.getOrLoad(secondKey) { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
        cache.getOrLoad(thirdKey) { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
        cache.getOrLoad(firstKey) { firstLoads++; Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }

        assertEquals(2, firstLoads)
    }
}
