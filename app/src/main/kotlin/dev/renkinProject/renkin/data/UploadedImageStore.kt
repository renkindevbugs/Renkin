package dev.renkinProject.renkin.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Persistent gallery of user-uploaded images. Images are decoded once on import,
 * downscaled and stored as PNG files in the app's internal storage so they can be
 * reused for any application later.
 */
object UploadedImageStore {
    private const val DIRECTORY_NAME = "uploaded_images"

    /** The gallery directory — also read/written directly by the backup export/import. */
    fun directory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    fun list(context: Context): List<File> {
        return directory(context).listFiles { file -> file.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun save(context: Context, bitmap: Bitmap): File {
        val file = File(directory(context), "img_${System.currentTimeMillis()}_${(0..9999).random()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun delete(file: File): Boolean {
        return file.delete()
    }
}
