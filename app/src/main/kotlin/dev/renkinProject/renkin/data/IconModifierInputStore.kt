package dev.renkinProject.renkin.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dev.renkinProject.renkin.data.transfer.ensureBackupSpace
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredModifierArtwork
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

internal class IconModifierInputStore(private val context: Context) {
    internal data class Prepared(val document: String, val artwork: StoredModifierArtwork)

    suspend fun prepare(state: IconModifierInput): Prepared? = withContext(Dispatchers.IO) {
        try {
            if (!StoredIconModifierInput.supportsOptions(state.options)) return@withContext null
            val (retained, live) = synchronized(state) { state.storedInput to state.liveInput }
            val artwork = retained?.takeIf { exists(it) } ?: live?.let { input ->
                val payload = input.toDbString()
                if (payload.isEmpty() || payload.length > MAX_ARTWORK_BYTES) return@withContext null
                StoredModifierArtwork(write(payload), input !is BitmapIconDrawable, input.isAdaptiveIcon())
            } ?: return@withContext null
            val encoded = StoredIconModifierInput.encode(state, artwork) ?: return@withContext null
            Prepared(encoded, artwork)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("IconModifierInputStore", "Optional modifier input was not saved", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.error("IconModifierInputStore", "Optional modifier input exceeded available memory", error)
            null
        }
    }

    // Only the persistence owner calls this after the row referencing the synced file commits.
    fun accept(state: IconModifierInput, prepared: Prepared) = state.cacheStoredArtwork(prepared.artwork)

    fun exists(artwork: StoredModifierArtwork): Boolean = file(context, artwork.hash).let {
        it.isFile && it.length() in 1..MAX_ARTWORK_BYTES
    }

    private suspend fun write(payload: String): String {
        val root = directory(context)
        ensureBackupSpace(context.filesDir, payload.length.toLong() * 3)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create modifier input storage")
        val temporary = File(root, ".${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary).use { output ->
                val writer = DigestOutputStream(output, digest).writer(Charsets.UTF_8).buffered()
                var offset = 0
                while (offset < payload.length) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(DEFAULT_BUFFER_SIZE, payload.length - offset)
                    writer.write(payload, offset, count)
                    offset += count
                    if (temporary.length() > MAX_ARTWORK_BYTES) throw IOException("Modifier input is too large")
                }
                writer.flush()
                if (temporary.length() > MAX_ARTWORK_BYTES) throw IOException("Modifier input is too large")
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = file(context, hash)
            if (!validFile(target, hash)) Os.rename(temporary.absolutePath, target.absolutePath)
            syncDirectory(root)
            syncDirectory(context.filesDir)
            return hash
        } finally { temporary.delete() }
    }

    companion object {
        const val ARCHIVE_DIR = "icon-inputs"
        const val MAX_ARTWORK_BYTES = 32L * 1024 * 1024

        fun directory(context: Context): File = File(context.filesDir, ARCHIVE_DIR)

        fun file(context: Context, hash: String): File {
            require(StoredIconModifierInput.validHash(hash))
            return File(directory(context), "$hash.input")
        }

        fun validName(name: String): Boolean = name.endsWith(".input") &&
            StoredIconModifierInput.validHash(name.removeSuffix(".input"))

        suspend fun validFile(file: File, hash: String): Boolean {
            if (!StoredIconModifierInput.validHash(hash) || !file.isFile || file.length() !in 1..MAX_ARTWORK_BYTES) return false
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (bytes > MAX_ARTWORK_BYTES) return false
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == hash
        }

        // Run only after startup recovery, before live drafts/undo can reference unsaved files.
        suspend fun cleanupAfterProcessStart(context: Context, documents: List<String>) {
            val wanted = documents.mapNotNull { StoredIconModifierInput.artwork(it)?.hash }.toSet()
            for (file in directory(context).listFiles().orEmpty()) {
                currentCoroutineContext().ensureActive()
                if ((validName(file.name) && file.name.removeSuffix(".input") !in wanted) ||
                    (file.name.startsWith('.') && file.name.endsWith(".tmp"))) {
                    if (!file.delete()) Log.info("IconModifierInputStore", "Cannot remove unused input ${file.name}")
                }
            }
        }

        private fun syncDirectory(directory: File) {
            if (!directory.isDirectory) throw IOException("Cannot sync modifier input directory")
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
