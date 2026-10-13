package dev.renkinProject.renkin.packages

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launcher icons of installed packages for small chips repeated across many rows (the pack
 * browser, watch lists, the about dialog). Each is decoded once off the main thread and cached
 * process-wide, keyed by the package's last-update time so an updated or reinstalled pack is
 * re-decoded instead of showing a stale icon.
 */
internal object PackIconLoader {
    private const val ICON_PX = 96

    private data class Cached(val stamp: Long, val bitmap: ImageBitmap?)
    // Read on Main for the first frame while loads write from IO threads.
    private val cache = ConcurrentHashMap<String, Cached>()

    fun cached(packageName: String): ImageBitmap? = cache[packageName]?.bitmap

    /** The package's launcher icon, or null when the package is gone or its icon cannot be drawn. */
    suspend fun load(context: Context, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val packageManager = context.packageManager
            val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            cache[packageName]?.takeIf { it.stamp == stamp }?.let { return@withContext it.bitmap }
            val bitmap = packageManager.getApplicationIcon(packageName)
                .toSafeBitmapOrNull(ICON_PX, ICON_PX)?.asImageBitmap()
            cache[packageName] = Cached(stamp, bitmap)
            bitmap
        } catch (_: Exception) {
            cache[packageName] = Cached(0L, null)
            null
        }
    }
}
