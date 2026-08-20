package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shared low-level icon/bitmap rendering, reused anywhere an app or icon-pack icon is shown
// (the app list, watch list/editor/apply modal, the about dialog, …). Decoding once here —
// downscaled to the on-screen size — keeps icon-heavy lists cheap and off the main thread.

internal data class AppBitmapCacheKey(
    val component: String,
    val iconId: Int,
    // Identity changes when the launcher metadata or a rendered icon changes, invalidating an
    // otherwise unchanged id without retaining entries forever (LRU).
    val drawable: Any,
    val targetPx: Int
)

internal class AppBitmapMemoryCache(maxSizeBytes: Int = DEFAULT_APP_BITMAP_CACHE_BYTES) {
    private val cache = object : LruCache<AppBitmapCacheKey, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: AppBitmapCacheKey, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: AppBitmapCacheKey): Bitmap? = cache.get(key)

    fun getOrLoad(key: AppBitmapCacheKey, load: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        return load()?.also { cache.put(key, it) }
    }

    companion object {
        private const val DEFAULT_APP_BITMAP_CACHE_BYTES = 8 * 1024 * 1024
    }
}

private val appBitmapCache = AppBitmapMemoryCache()

/**
 * The app's launcher icon as an [ImageBitmap], decoded to [size] (never upscaled past the
 * icon's own resolution) so lists of icons stay light. Re-decoded when the app's launcher
 * metadata changes. A process-wide, memory-bounded cache survives LazyColumn disposing
 * off-screen rows, while the first rasterisation runs on a worker thread.
 */
@Composable
internal fun rememberAppBitmap(app: PackageInfoStruct, size: Dp = 54.dp): ImageBitmap? {
    val density = LocalDensity.current
    val target = with(density) { size.roundToPx() }
    val key = remember(app.key, app.iconID, app.icon, target) {
        AppBitmapCacheKey(app.key, app.iconID, app.icon, target)
    }
    var bitmap by remember(key) { mutableStateOf(appBitmapCache.get(key)?.asImageBitmap()) }

    LaunchedEffect(key) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.Default) {
                appBitmapCache.getOrLoad(key) {
                    val px = app.icon.intrinsicWidth.let { if (it in 1 until target) it else target }
                    // Drawable rasterisation temporarily mutates bounds. The same app can be
                    // requested at several sizes by home/watch surfaces, so serialize on it.
                    synchronized(app.icon) { app.icon.toSafeBitmapOrNull(px, px) }
                }
            }
            bitmap = loaded?.asImageBitmap()
        }
    }

    return bitmap
}

private val createdIconCache = AppBitmapMemoryCache()

/**
 * The icon Renkin made for [app], decoded to [size]. Rasterising instead of handing the list a
 * vector painter is what keeps a newly generated icon from appearing small and growing into
 * place: by the time it is drawn it is already a bitmap of the right size.
 *
 * Null while it loads, and for apps with no icon of their own.
 */
@Composable
internal fun rememberCreatedIconBitmap(app: PackageInfoStruct, size: Dp): ImageBitmap? {
    return rememberIconBitmap(app, app.createdIcon, size)
}

/** Rasterises any generated layer (base or rendered) through the shared memory-bounded cache. */
@Composable
internal fun rememberIconBitmap(
    app: PackageInfoStruct,
    icon: IconPackDrawable?,
    size: Dp
): ImageBitmap? {
    val target = with(LocalDensity.current) { size.roundToPx() }
    val key = remember(app.key, app.internalVersion, icon, target) {
        icon?.let { AppBitmapCacheKey(app.key, app.internalVersion, it, target) }
    }
    var bitmap by remember(key) {
        mutableStateOf(key?.let { createdIconCache.get(it)?.asImageBitmap() })
    }

    LaunchedEffect(key) {
        if (key != null && bitmap == null && icon != null) {
            val loaded = withContext(Dispatchers.Default) {
                createdIconCache.getOrLoad(key) {
                    runCatching {
                        val full = icon.previewBitmap()
                        // Caching the generator's 256px output would fill the budget after a
                        // couple of dozen icons and re-decode on every scroll.
                        if (full.width > target) {
                            full.scale(target, target)
                        } else {
                            full
                        }
                    }.getOrNull()
                }
            }
            bitmap = loaded?.asImageBitmap()
        }
    }

    return bitmap
}

@Composable
internal fun AppIcon(app: PackageInfoStruct?, size: Dp) {
    val bitmap = app?.let { rememberAppBitmap(it, size) }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = RoundedCornerShape(size / 4),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {}
    }
}

// Pack icons appear as small chips repeated across many rows (the pack browser, watch lists,
// the about dialog). Decode each once OFF the main thread (decoding in remember{} blocked the
// UI while the editor dialog opened) and cache it process-wide, keyed by the pack's last-update
// time so an updated or reinstalled pack is re-decoded instead of showing a stale icon.
private const val PACK_ICON_PX = 96

private data class CachedPackIcon(val stamp: Long, val bitmap: ImageBitmap?)
private val packIconCache = mutableMapOf<String, CachedPackIcon>()

/**
 * The single shared pack-icon loader: a package's launcher icon as an [ImageBitmap], decoded
 * once off the main thread and cached. Null until it has loaded (and if the package is gone).
 */
@Composable
internal fun rememberPackIcon(packPackage: String): ImageBitmap? {
    val context = getCurrentContext()
    var icon by remember(packPackage) { mutableStateOf(packIconCache[packPackage]?.bitmap) }
    LaunchedEffect(packPackage) {
        icon = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val stamp = pm.getPackageInfo(packPackage, 0).lastUpdateTime
                packIconCache[packPackage]?.takeIf { it.stamp == stamp }?.let { return@withContext it.bitmap }
                val bitmap = pm.getApplicationIcon(packPackage)
                    .toSafeBitmapOrNull(PACK_ICON_PX, PACK_ICON_PX)?.asImageBitmap()
                packIconCache[packPackage] = CachedPackIcon(stamp, bitmap)
                bitmap
            } catch (_: Exception) {
                packIconCache[packPackage] = CachedPackIcon(0L, null)
                null
            }
        }
    }
    return icon
}

@Composable
internal fun PackIconImage(packPackage: String, size: Dp) {
    val bitmap = rememberPackIcon(packPackage)
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = RoundedCornerShape(size / 4),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {}
    }
}
