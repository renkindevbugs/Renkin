package dev.renkinProject.renkin.drawable

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconPreviewMemoryCacheTest {
    private open class TestIcon(val pixels: Int = 2) : IconPackDrawable() {
        var renders = 0
        var failure: Throwable? = null
        override fun previewBitmap(sizePx: Int): Bitmap {
            renders++
            failure?.let { throw it }
            return Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        }
        override fun toBitmap(): Bitmap = previewBitmap(pixels)
        override fun toDbString(): String = ""
        @Composable override fun getPainter(): Painter = ColorPainter(Color.Transparent)
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    @Test
    fun repeatedRequestReusesBitmapButDifferentSizeAndRevisionDoNot() {
        val cache = IconPreviewMemoryCache(1024)
        val icon = TestIcon()
        val first = cache.getOrRender(IconPreviewCacheKey(icon, 56))
        assertSame(first, cache.getOrRender(IconPreviewCacheKey(icon, 56)))
        assertNotSame(first, cache.getOrRender(IconPreviewCacheKey(icon, 72)))
        assertNotSame(first, cache.getOrRender(IconPreviewCacheKey(icon, 56, 1)))
        assertEquals(3, icon.renders)
    }

    @Test
    fun rawDrawFrameNeverAliasesAPreviewWithTheSameRevision() {
        val cache = IconPreviewMemoryCache(1024)
        val icon = TestIcon()
        val preview = cache.getOrRender(IconPreviewCacheKey(icon, 56, revision = 1))
        val raw = cache.getOrRender(IconPreviewCacheKey(icon, 56, revision = 1, rawGeometry = true))
        assertNotSame(preview, raw)
        assertSame(raw, cache.getOrRender(IconPreviewCacheKey(icon, 56, revision = 1, rawGeometry = true)))
        assertEquals(2, icon.renders)
    }

    @Test
    fun drawableValueEqualityCannotAliasAnotherInstance() {
        class EqualIcon : TestIcon() {
            override fun equals(other: Any?): Boolean = other is EqualIcon
            override fun hashCode(): Int = 1
        }
        val first = EqualIcon()
        val other = EqualIcon()
        assertEquals(first, other)
        val cache = IconPreviewMemoryCache(1024)
        assertNotSame(cache.getOrRender(IconPreviewCacheKey(first, 56)),
            cache.getOrRender(IconPreviewCacheKey(other, 56)))
    }

    @Test
    fun byteBudgetEvictsWithoutRecyclingVisibleBitmap() {
        val cache = IconPreviewMemoryCache(32)
        val icons = List(3) { TestIcon() }
        val firstKey = IconPreviewCacheKey(icons[0], 56)
        val visible = cache.getOrRender(firstKey)!!
        icons.drop(1).forEach { cache.getOrRender(IconPreviewCacheKey(it, 56)) }
        assertNull(cache.get(firstKey))
        assertFalse(visible.isRecycled)
        assertNotSame(visible, cache.getOrRender(firstKey))
    }

    @Test
    fun detailEvictionCannotEvictThumbnail() {
        val small = TestIcon()
        val key = IconPreviewCacheKey(small, 56)
        val thumbnail = IconPreviewCache.getOrRender(key)
        val large = List(3) { TestIcon(500) }
        large.forEach { IconPreviewCache.getOrRender(IconPreviewCacheKey(it, 500)) }
        assertSame(thumbnail, IconPreviewCache.get(key))
        assertNull(IconPreviewCache.get(IconPreviewCacheKey(large[0], 500)))
        assertEquals(1, small.renders)
    }

    @Test
    fun renderFailureReturnsEmptyAndLaterRecoveryCanRender() {
        val icon = TestIcon().apply { failure = IllegalStateException("Broken drawable") }
        val key = IconPreviewCacheKey(icon, 56)
        assertNull(IconPreviewCache.getOrRender(key))
        icon.failure = null
        assertNotNull(IconPreviewCache.getOrRender(key))
        assertEquals(2, icon.renders)
    }

    @Test
    fun allocationFailureClearsBothBudgetsWithoutRecyclingVisibleImages() {
        val small = TestIcon()
        val large = TestIcon(500)
        val smallKey = IconPreviewCacheKey(small, 56)
        val largeKey = IconPreviewCacheKey(large, 500)
        val visibleSmall = IconPreviewCache.getOrRender(smallKey)!!
        val visibleLarge = IconPreviewCache.getOrRender(largeKey)!!
        val failed = TestIcon().apply { failure = OutOfMemoryError("Simulated allocation failure") }
        assertNull(IconPreviewCache.getOrRender(IconPreviewCacheKey(failed, 56)))
        assertNull(IconPreviewCache.get(smallKey))
        assertNull(IconPreviewCache.get(largeKey))
        assertFalse(visibleSmall.isRecycled)
        assertFalse(visibleLarge.isRecycled)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverTurnedIntoAnEmptyPreview() {
        val icon = TestIcon().apply { failure = CancellationException("Cancelled") }
        IconPreviewCache.getOrRender(IconPreviewCacheKey(icon, 56))
    }
}
