package dev.renkinProject.renkin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.renkinProject.renkin.drawable.shrinkIfBiggerThan
import dev.renkinProject.renkin.extension.toDrawable
import dev.renkinProject.renkin.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of importing picked or shared images into the upload gallery. */
data class UploadImport(val added: List<File>, val anyFailed: Boolean)

/** The upload gallery's storage, so gallery composables never read or write files themselves. */
interface UploadedImageLibrary {
    /** Gallery images, newest first, after clearing trash left over from an interrupted session. */
    suspend fun load(): List<File>
    suspend fun list(): List<File>
    suspend fun import(uris: List<Uri>): UploadImport
    suspend fun moveToTrash(files: List<File>): List<UploadedImageStore.TrashEntry>
    suspend fun restore(entries: List<UploadedImageStore.TrashEntry>)
    suspend fun permanentlyDelete(entries: List<UploadedImageStore.TrashEntry>)
    suspend fun decode(path: String): Bitmap?
    /** A half-size decode for small grid cells. */
    suspend fun thumbnail(file: File): Bitmap?
}

class StoredUploadedImageLibrary(private val context: Context) : UploadedImageLibrary {
    override suspend fun load(): List<File> = withContext(Dispatchers.IO) {
        UploadedImageStore.cleanupTrash(context)
        UploadedImageStore.list(context)
    }

    override suspend fun list(): List<File> = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }

    override suspend fun import(uris: List<Uri>): UploadImport = withContext(Dispatchers.IO) {
        var anyFailed = false
        val added = uris.mapNotNull { uri ->
            try {
                decodeImportedImage(context, uri)
                    ?.toDrawable(context.resources)
                    ?.shrinkIfBiggerThan(MAX_UPLOADED_IMAGE_SIZE)
                    ?.let { UploadedImageStore.save(context, it) }
                    .also { if (it == null) anyFailed = true }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A revoked, one-shot or broken provider URI fails only that image.
                Log.error("UploadedImageLibrary", "Cannot import an image", error)
                anyFailed = true
                null
            }
        }
        UploadImport(added, anyFailed)
    }

    override suspend fun moveToTrash(files: List<File>): List<UploadedImageStore.TrashEntry> =
        withContext(Dispatchers.IO) { UploadedImageStore.moveToTrash(context, files) }

    override suspend fun restore(entries: List<UploadedImageStore.TrashEntry>) =
        withContext(Dispatchers.IO) { UploadedImageStore.restore(entries) }

    override suspend fun permanentlyDelete(entries: List<UploadedImageStore.TrashEntry>) =
        withContext(Dispatchers.IO) { UploadedImageStore.permanentlyDelete(entries) }

    override suspend fun decode(path: String): Bitmap? = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }

    override suspend fun thumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
    }

    private companion object {
        const val MAX_UPLOADED_IMAGE_SIZE = 500
    }
}
