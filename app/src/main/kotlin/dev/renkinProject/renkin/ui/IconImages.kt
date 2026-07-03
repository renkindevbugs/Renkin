package dev.renkinProject.renkin.ui

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
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shared low-level icon/bitmap rendering, reused anywhere an app or icon-pack icon is shown
// (the app list, watch list/editor/apply modal, the about dialog, …). Decoding once here —
// downscaled to the on-screen size — keeps icon-heavy lists cheap and off the main thread.

/**
 * The app's launcher icon as an [ImageBitmap], decoded to [size] (never upscaled past the
 * icon's own resolution) so lists of icons stay light. Re-decoded when the app's created
 * icon changes ([PackageInfoStruct.internalVersion]).
 */
@Composable
internal fun rememberAppBitmap(app: PackageInfoStruct, size: Dp = 54.dp): ImageBitmap? {
    val density = LocalDensity.current
    return remember(app.packageName, app.internalVersion, density, size) {
        val target = with(density) { size.roundToPx() }
        val px = app.icon.intrinsicWidth.let { if (it in 1 until target) it else target }
        app.icon.toSafeBitmapOrNull(px, px)?.asImageBitmap()
    }
}

@Composable
internal fun AppIcon(app: PackageInfoStruct?, fallbackPackage: String, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(app?.packageName ?: fallbackPackage, app?.internalVersion) {
        app?.icon?.toSafeBitmapOrNull()
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
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
