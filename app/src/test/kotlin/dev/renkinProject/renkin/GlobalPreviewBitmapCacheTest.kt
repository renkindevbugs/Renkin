package dev.renkinProject.renkin

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalPreviewBitmapCacheTest {

    private fun key(component: String) = GlobalPreviewCacheKey(
        component = component,
        iconVersion = 1,
        sourceOptions = null,
        modifierOptions = null,
        targetPx = 56
    )

    @Test
    fun cachedPreview_isReusedAfterTileLeavesComposition() {
        val cache = GlobalPreviewBitmapCache(maxSizeBytes = 1024)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cacheKey = key("com.app/com.app.Main")

        cache.put(cacheKey, bitmap)

        assertSame(bitmap, cache.get(cacheKey))
    }

    @Test
    fun byteLimit_evictsLeastRecentlyUsedPreview() {
        val cache = GlobalPreviewBitmapCache(maxSizeBytes = 32)
        val firstKey = key("com.first/com.first.Main")
        val secondKey = key("com.second/com.second.Main")
        val thirdKey = key("com.third/com.third.Main")

        cache.put(firstKey, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        cache.put(secondKey, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        cache.get(firstKey)
        cache.put(thirdKey, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))

        assertNotNull(cache.get(firstKey))
        assertNull(cache.get(secondKey))
    }

    @Test
    fun clear_removesObsoleteModifierVariants() {
        val cache = GlobalPreviewBitmapCache(maxSizeBytes = 1024)
        val cacheKey = key("com.app/com.app.Main")
        cache.put(cacheKey, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))

        cache.clear()

        assertNull(cache.get(cacheKey))
    }

    @Test
    fun previewParallelism_isBoundedByCpuAndMaximum() {
        assertEquals(1, previewParallelism(1))
        assertEquals(3, previewParallelism(3))
        assertEquals(4, previewParallelism(8))
    }
}
