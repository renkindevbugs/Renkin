package dev.renkinProject.renkin.drawable

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

// [rawGeometry] keeps draw() frames, which skip the in-app preview zoom, apart from preview bitmaps
// of the same icon; [revision] is a caller-owned counter (for example an app's reload version).
internal class IconPreviewCacheKey(
    icon: IconPackDrawable,
    val targetPx: Int,
    val revision: Int = 0,
    val rawGeometry: Boolean = false
) {
    private val reference = WeakReference(icon)
    private val identityHash = System.identityHashCode(icon)
    val icon: IconPackDrawable? get() = reference.get()

    override fun hashCode(): Int = 31 * (31 * (31 * identityHash + targetPx) + revision) + rawGeometry.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IconPreviewCacheKey || targetPx != other.targetPx || revision != other.revision ||
            rawGeometry != other.rawGeometry
        ) return false
        val live = icon ?: return false
        return live === other.icon
    }
}

internal class IconPreviewMemoryCache(maxSizeBytes: Int) {
    private val cache = object : LruCache<IconPreviewCacheKey, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: IconPreviewCacheKey, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: IconPreviewCacheKey): Bitmap? = cache.get(key)

    fun getOrRender(
        key: IconPreviewCacheKey,
        render: (IconPackDrawable) -> Bitmap = { it.previewBitmap(key.targetPx) }
    ): Bitmap? {
        cache.get(key)?.let { return it }
        val icon = key.icon ?: return null
        return synchronized(icon) {
            cache.get(key) ?: render(icon).also { cache.put(key, it) }
        }
    }

    fun clear() = cache.evictAll()
}

internal object IconPreviewCache {
    private val thumbnails = IconPreviewMemoryCache(6 * 1024 * 1024)
    private val details = IconPreviewMemoryCache(2 * 1024 * 1024)

    private fun cacheFor(key: IconPreviewCacheKey): IconPreviewMemoryCache =
        if (key.targetPx <= 256) thumbnails else details

    fun get(key: IconPreviewCacheKey): Bitmap? = cacheFor(key).get(key)

    fun getOrRender(
        key: IconPreviewCacheKey,
        render: (IconPackDrawable) -> Bitmap = { it.previewBitmap(key.targetPx) }
    ): Bitmap? = try {
        cacheFor(key).getOrRender(key, render)
    } catch (error: CancellationException) {
        throw error
    } catch (_: OutOfMemoryError) {
        thumbnails.clear()
        details.clear()
        null
    } catch (error: Exception) {
        Log.error("IconPreviewCache", "Could not render icon preview", error)
        null
    }
}

private class EmptyIconPreviewPainter(override val intrinsicSize: Size) : Painter() {
    override fun DrawScope.onDraw() = Unit
}

@Composable
internal fun IconPackDrawable.rememberPreviewPainter(
    sizePx: Int,
    intrinsicSize: Size = Size(sizePx.toFloat(), sizePx.toFloat())
): Painter {
    val key = remember(this, sizePx) { IconPreviewCacheKey(this, sizePx) }
    var bitmap by remember { mutableStateOf(IconPreviewCache.get(key)) }
    LaunchedEffect(key) {
        // Keep the previous frame while rendering; a failed request withdraws that stale image.
        bitmap = IconPreviewCache.get(key) ?: withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            IconPreviewCache.getOrRender(key)
        }
    }
    val visibleBitmap = IconPreviewCache.get(key) ?: bitmap
    return remember(visibleBitmap, intrinsicSize) {
        visibleBitmap?.let { BitmapPainter(it.asImageBitmap()) } ?: EmptyIconPreviewPainter(intrinsicSize)
    }
}
