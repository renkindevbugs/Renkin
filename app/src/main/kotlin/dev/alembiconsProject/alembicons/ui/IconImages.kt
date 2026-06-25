package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct

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

/** A package's launcher icon as an [ImageBitmap], decoded to [size]. Null if the package is gone. */
@Composable
internal fun rememberPackBitmap(packPackage: String, size: Dp = 54.dp): ImageBitmap? {
    val context = getCurrentContext()
    val density = LocalDensity.current
    return remember(packPackage, density, size) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packPackage)
            val target = with(density) { size.roundToPx() }
            val px = drawable.intrinsicWidth.let { if (it in 1 until target) it else target }
            drawable.toSafeBitmapOrNull(px, px)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
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

// Pack icons appear as small chips repeated across many rule cards, and the LazyColumn
// re-creates them on every scroll. Decode each pack icon once (small) and cache it
// process-wide so scrolling doesn't keep hitting PackageManager / re-decoding bitmaps.
private val packIconCache = mutableMapOf<String, ImageBitmap?>()

private fun packIconBitmap(context: android.content.Context, packPackage: String): ImageBitmap? =
    packIconCache.getOrPut(packPackage) {
        try {
            context.packageManager.getApplicationIcon(packPackage)
                .toSafeBitmapOrNull(72, 72)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

@Composable
internal fun PackIconImage(packPackage: String, size: androidx.compose.ui.unit.Dp) {
    val context = getCurrentContext()
    val bitmap = remember(packPackage) { packIconBitmap(context, packPackage) }
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
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {}
    }
}
