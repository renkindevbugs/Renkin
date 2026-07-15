package dev.renkinProject.renkin.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Persistent gallery of user-uploaded images. Images are decoded once on import,
 * downscaled and stored as PNG files in the app's internal storage so they can be
 * reused for any application later.
 */
object UploadedImageStore {
    private const val DIRECTORY_NAME = "uploaded_images"
    private const val TRASH_DIRECTORY_NAME = ".trash"

    data class TrashEntry(val original: File, val trashed: File)

    /** The gallery directory — also read/written directly by the backup export/import. */
    fun directory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    fun list(context: Context): List<File> {
        val directory = directory(context)
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        return directory.listFiles { file -> file.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun save(context: Context, bitmap: Bitmap): File {
        val file = File(directory(context), "img_${System.currentTimeMillis()}_${(0..9999).random()}.png")
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("PNG compression failed")
                }
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Could not finalize uploaded image")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        return file
    }

    fun moveToTrash(context: Context, files: List<File>): List<TrashEntry> {
        val trash = File(directory(context), TRASH_DIRECTORY_NAME).apply { mkdirs() }
        return files.mapNotNull { original ->
            val trashed = File(trash, original.name)
            if (original.renameTo(trashed)) TrashEntry(original, trashed) else null
        }
    }

    fun restore(entries: List<TrashEntry>) {
        entries.forEach { entry -> entry.trashed.renameTo(entry.original) }
    }

    fun permanentlyDelete(entries: List<TrashEntry>) {
        entries.forEach { entry -> entry.trashed.delete() }
    }

    fun cleanupTrash(context: Context) {
        File(directory(context), TRASH_DIRECTORY_NAME).listFiles()?.forEach { it.delete() }
    }

    fun delete(file: File): Boolean {
        return file.delete()
    }
}
