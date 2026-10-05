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

    private var trashGeneration = 0L

    data class TrashEntry(val original: File, val trashed: File, val generation: Long)

    internal suspend fun invalidatePendingTrash(): Unit = StorageOperationGate.run { trashGeneration += 1 }

    /** The gallery directory — also read/written directly by the backup export/import. */
    fun directory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    suspend fun list(context: Context): List<File> = StorageOperationGate.run {
        val directory = directory(context)
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        return@run directory.listFiles { file -> file.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    suspend fun save(context: Context, bitmap: Bitmap): File = StorageOperationGate.run {
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
        return@run file
    }

    suspend fun moveToTrash(context: Context, files: List<File>): List<TrashEntry> = StorageOperationGate.run {
        val trash = File(directory(context), TRASH_DIRECTORY_NAME).apply { mkdirs() }
        return@run files.mapNotNull { original ->
            val trashed = File(trash, original.name)
            if (original.renameTo(trashed)) TrashEntry(original, trashed, trashGeneration) else null
        }
    }

    suspend fun restore(entries: List<TrashEntry>): Unit = StorageOperationGate.run {
        entries.filter { it.generation == trashGeneration }.forEach { entry -> entry.trashed.renameTo(entry.original) }
    }

    suspend fun permanentlyDelete(entries: List<TrashEntry>): Unit = StorageOperationGate.run {
        entries.filter { it.generation == trashGeneration }.forEach { entry -> entry.trashed.delete() }
    }

    suspend fun cleanupTrash(context: Context): Unit = StorageOperationGate.run {
        File(directory(context), TRASH_DIRECTORY_NAME).listFiles()?.forEach { it.delete() }
    }

    suspend fun delete(file: File): Boolean = StorageOperationGate.run {
        file.delete()
    }
}
