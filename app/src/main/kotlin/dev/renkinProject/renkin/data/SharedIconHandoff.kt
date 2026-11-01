package dev.renkinProject.renkin.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hands an icon to an external image editor through this app's FileProvider. */
object SharedIconHandoff {
    /** A readable content URI for [bitmap], or null when it cannot be written. */
    suspend fun write(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Previous handoffs are stale once a new one starts — don't let them pile up in the cache.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "icon_${System.currentTimeMillis()}.png")
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG compression failed" }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        }.getOrNull()
    }
}
