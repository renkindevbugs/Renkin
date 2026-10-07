package dev.renkinProject.renkin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import dev.renkinProject.renkin.data.transfer.ensureBackupSpace
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.parser.XmlNodeParser
import dev.renkinProject.renkin.xml.XmlDecoder
import dev.renkinProject.renkin.xml.XmlNode
import androidx.compose.ui.graphics.Color
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

    internal sealed interface ReadInput {
        data class Live(val icon: IconPackDrawable) : ReadInput
        data class Stored(val bytes: ByteArray, val artwork: StoredModifierArtwork) : ReadInput
    }

    suspend fun read(state: IconModifierInput): ReadInput? = withContext(Dispatchers.IO) {
        try {
            val (retained, live) = synchronized(state) { state.storedInput to state.liveInput }
            if (live != null) {
                val descriptor = StoredModifierArtwork("0".repeat(64), live !is BitmapIconDrawable,
                    live.isAdaptiveIcon(), (live as? BitmapIconDrawable)?.previewScale ?: 1f)
                return@withContext live.takeIf { StoredIconModifierInput.encode(state, descriptor) != null }?.let { ReadInput.Live(it) }
            }
            val artwork = retained ?: return@withContext null
            if (StoredIconModifierInput.encode(state, artwork) == null) return@withContext null
            val source = file(context, artwork.hash)
            val size = source.length()
            if (!source.isFile || size !in 1..MAX_ARTWORK_BYTES ||
                source.canonicalFile.parentFile != directory(context).canonicalFile) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            val payload = ByteArray(size.toInt())
            source.inputStream().buffered().use { input ->
                var offset = 0
                while (offset < payload.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(payload, offset, minOf(DEFAULT_BUFFER_SIZE, payload.size - offset))
                    if (count < 0) return@withContext null
                    digest.update(payload, offset, count)
                    offset += count
                }
                if (input.read() != -1) return@withContext null
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != artwork.hash) return@withContext null
            currentCoroutineContext().ensureActive()
            ReadInput.Stored(payload, artwork)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("IconModifierInputStore", "Optional modifier input could not be read", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.error("IconModifierInputStore", "Optional modifier input exceeded available memory while reading", error)
            null
        }
    }

    suspend fun decode(input: ReadInput): IconPackDrawable? = withContext(Dispatchers.Default) {
        try {
            currentCoroutineContext().ensureActive()
            if (input is ReadInput.Live) return@withContext input.icon
            input as ReadInput.Stored
            val artwork = input.artwork
            if (input.bytes.any { it < 0 }) return@withContext null
            val decoded = if (artwork.isXml) {
                val owner = currentCoroutineContext()
                val nodes = XmlDecoder.fromBase64(input.bytes, MAX_XML_NODES, MAX_XML_DEPTH) { owner.ensureActive() }
                val pending = ArrayDeque<XmlNode>()
                pending.add(nodes)
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val node = pending.removeLast()
                    if (node.name == "inset") node.getAttributeValue("drawable")?.let(::boundedBitmapBytes)
                    pending.addAll(node.children)
                }
                XmlNodeParser.parse(context.resources, nodes, Color.Unspecified)
            } else {
                BitmapIconDrawable(context.resources, decodeBitmap(input.bytes), artwork.isAdaptive, artwork.previewScale)
            } ?: return@withContext null
            if ((decoded !is BitmapIconDrawable) != artwork.isXml || decoded.isAdaptiveIcon() != artwork.isAdaptive) {
                return@withContext null
            }
            currentCoroutineContext().ensureActive()
            decoded
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("IconModifierInputStore", "Optional modifier input could not be loaded", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.error("IconModifierInputStore", "Optional modifier input exceeded available memory while loading", error)
            null
        }
    }

    suspend fun prepare(state: IconModifierInput): Prepared? = withContext(Dispatchers.IO) {
        try {
            if (!StoredIconModifierInput.supportsOptions(state.options)) return@withContext null
            val (retained, live) = synchronized(state) { state.storedInput to state.liveInput }
            val artwork = retained?.takeIf { exists(it) } ?: live?.let { input ->
                val payload = input.toDbString()
                if (payload.isEmpty() || payload.length > MAX_ARTWORK_BYTES) return@withContext null
                StoredModifierArtwork(
                    write(payload), input !is BitmapIconDrawable, input.isAdaptiveIcon(),
                    (input as? BitmapIconDrawable)?.previewScale ?: 1f
                )
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
        private const val MAX_XML_DEPTH = 64
        private const val MAX_XML_NODES = 100_000
        private const val MAX_BITMAP_PIXELS = 4096L * 4096

        private fun boundedBitmapBytes(encoded: String): ByteArray =
            validateBitmapBytes(Base64.decode(encoded, Base64.NO_WRAP))

        private fun validateBitmapBytes(bytes: ByteArray): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= MAX_BITMAP_PIXELS)
            return bytes
        }

        private fun decodeBitmap(encoded: ByteArray): Bitmap {
            val bytes = validateBitmapBytes(Base64.decode(encoded, Base64.NO_WRAP))
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }

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
