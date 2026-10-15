package dev.renkinProject.renkin.packages

import android.graphics.Bitmap
import android.util.LruCache

internal data class AppBitmapCacheKey(
    val component: String,
    val iconId: Int,
    val sourceRevision: Any,
    val targetPx: Int
)

internal class AppBitmapMemoryCache(maxSizeBytes: Int = 8 * 1024 * 1024) {
    private val cache = object : LruCache<AppBitmapCacheKey, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: AppBitmapCacheKey, value: Bitmap) = value.allocationByteCount
    }

    fun get(key: AppBitmapCacheKey): Bitmap? = cache.get(key)
    fun put(key: AppBitmapCacheKey, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() = cache.evictAll()
    fun getOrLoad(key: AppBitmapCacheKey, load: () -> Bitmap?): Bitmap? =
        cache.get(key) ?: load()?.also { cache.put(key, it) }
}
