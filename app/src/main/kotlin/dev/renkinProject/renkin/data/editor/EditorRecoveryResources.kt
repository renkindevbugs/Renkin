package dev.renkinProject.renkin.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class BoundEditorResources(val typeface: Typeface, val upload: Bitmap?)

/** Decoded selections never become paths. Only draft-owned bytes are admitted at this boundary. */
internal class EditorRecoveryResources(private val context: Context) {
    internal val androidResources get() = context.resources

    internal suspend fun bind(resources: Map<String, EditorDraftResource>, bitmapBudget: Long): BoundEditorResources {
        val font = requireNotNull(resources["font"]) { "Owned editor font is missing" }
        val typeface = withContext(Dispatchers.IO) {
            require(font.kind == EditorDraftResourceKind.FONT)
            val bytes = font.copyBytes()
            require(bytes.size >= 12) { "Invalid owned editor font" }
            currentCoroutineContext().ensureActive()
            // createFromFile supports the application's API 23 minimum. The file is private,
            // short-lived and named independently of any decoded font selection identifier.
            val file = File.createTempFile("editor-recovery-font-", ".font", context.cacheDir)
            try {
                FileOutputStream(file).use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(8192, bytes.size - offset)
                        output.write(bytes, offset, count); offset += count
                    }
                }
                currentCoroutineContext().ensureActive()
                val typeface = if (Build.VERSION.SDK_INT >= 26) {
                    // createFromFile can fall back to DEFAULT on newer Android versions.
                    requireNotNull(Typeface.Builder(file).build()) { "Invalid owned editor font data" }
                } else Typeface.createFromFile(file)
                typeface.also { currentCoroutineContext().ensureActive() }
            } finally {
                if (!file.delete()) Log.error("EditorRecoveryResources", "Temporary recovery font could not be removed")
            }
        }
        val upload = resources["upload"]?.let { resource ->
            withContext(Dispatchers.Default) {
                require(resource.kind == EditorDraftResourceKind.UPLOAD)
                val bytes = resource.copyBytes()
                val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
                require(bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }) { "Invalid owned editor upload" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                    bounds.outWidth.toLong() * bounds.outHeight * 4 <= minOf(EditorDraftCodec.MAX_ARTIFACT_BYTES, bitmapBudget)) { "Owned upload exceeds pixel limits" }
                currentCoroutineContext().ensureActive()
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false })) { "Invalid owned editor upload pixels" }
                try {
                    require(bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight && bitmap.config == Bitmap.Config.ARGB_8888)
                    currentCoroutineContext().ensureActive(); bitmap
                } catch (error: Exception) { bitmap.recycle(); throw error }
            }
        }
        return BoundEditorResources(typeface, upload)
    }
}
