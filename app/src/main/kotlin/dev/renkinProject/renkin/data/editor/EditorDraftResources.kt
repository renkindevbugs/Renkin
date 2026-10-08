package dev.renkinProject.renkin.data.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.icon.creator.FontCatalog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class EditorDraftResourceKind { UPLOAD, FONT }

/** Detached bytes only; selection references are attribution, never restore-time file authority. */
internal class EditorDraftResource internal constructor(
    val kind: EditorDraftResourceKind,
    val selectionReference: String,
    bytes: ByteArray
) {
    init {
        require(bytes.size.toLong() in 1..EditorDraftCodec.MAX_ARTIFACT_BYTES) { "Invalid editor resource size" }
        require(selectionReference.length <= EditorControlsCodec.MAX_TEXT_BYTES) { "Editor resource reference is too large" }
    }
    private val content = bytes.copyOf()
    val byteCount: Int get() = content.size
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(content).editorDraftHex()
    fun copyBytes(): ByteArray = content.copyOf()
}

/** Reads allowed source files without writing a draft or acquiring either profile/storage gate. */
internal class EditorDraftResources(private val context: Context) {
    suspend fun upload(path: String): EditorDraftResource = withContext(Dispatchers.IO) {
        val parent = context.filesDir.canonicalFile
        // Resolving the parent's Android alias is allowed; links below the owned filesDir are not.
        val ownedRoot = File(parent, UploadedImageStore.directoryPath(context).name)
        if (ownedRoot.canonicalFile != ownedRoot.absoluteFile) throw IOException("Invalid editor upload directory")
        val file = selectedFile(path, ownedRoot)
        if (file.extension != "png") throw IOException("Invalid editor upload selection")
        val bytes = readFile(file)
        require(bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
            "Invalid editor upload PNG"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Editor upload dimensions exceed limits" }
        EditorDraftResource(EditorDraftResourceKind.UPLOAD, path, bytes)
    }

    suspend fun font(reference: String): EditorDraftResource = withContext(Dispatchers.IO) {
        require(reference.length <= EditorControlsCodec.MAX_TEXT_BYTES) { "Editor font reference is too large" }
        val bytes = if (reference.isEmpty()) {
            context.resources.openRawResource(R.font.arcticonssans_regular).use { readStream(it) }
        } else {
            require(FontCatalog.systemFonts().any { it.path == reference }) { "Unknown editor font selection" }
            val root = File("/system/fonts").canonicalFile
            readFile(selectedFile(reference, root))
        }
        // Full Typeface validation belongs to the eventual binder, against these owned bytes.
        require(bytes.size >= 12 && fontSignatures.any { signature ->
            signature.indices.all { bytes[it] == signature[it] }
        }) { "Unsupported editor font data" }
        EditorDraftResource(EditorDraftResourceKind.FONT, reference, bytes)
    }

    private fun selectedFile(path: String, root: File): File {
        require(path.length <= EditorControlsCodec.MAX_TEXT_BYTES) { "Editor resource reference is too large" }
        val selected = File(path)
        if (!selected.isAbsolute || selected.absoluteFile.parentFile?.canonicalFile != root) {
            throw IOException("Editor resource is outside its allowed directory")
        }
        val file = File(root, selected.name)
        if (selected.canonicalFile != file || file.canonicalFile != file.absoluteFile) {
            throw IOException("Invalid editor resource path")
        }
        return file
    }

    private suspend fun readFile(file: File): ByteArray {
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            val before = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_size !in 1..EditorDraftCodec.MAX_ARTIFACT_BYTES) {
                throw IOException("Editor resource is not a bounded regular file")
            }
            val bytes = ByteArray(before.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = Os.read(descriptor, bytes, offset, minOf(8192, bytes.size - offset))
                if (count <= 0) throw IOException("Editor resource was truncated")
                offset += count
            }
            if (Os.read(descriptor, ByteArray(1), 0, 1) != 0) throw IOException("Editor resource grew")
            val after = Os.fstat(descriptor)
            val selected = Os.stat(file.absolutePath)
            if (before.st_size != after.st_size || before.st_mtime != after.st_mtime || before.st_ctime != after.st_ctime ||
                after.st_size != selected.st_size || after.st_mtime != selected.st_mtime || after.st_ctime != selected.st_ctime ||
                before.st_ino != selected.st_ino || before.st_dev != selected.st_dev || file.canonicalFile != file.absoluteFile) {
                throw IOException("Editor resource changed during capture")
            }
            currentCoroutineContext().ensureActive()
            return bytes
        } finally {
            Os.close(descriptor)
        }
    }

    private suspend fun readStream(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > EditorDraftCodec.MAX_ARTIFACT_BYTES) throw IOException("Editor font exceeds limits")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
        val fontSignatures = listOf(byteArrayOf(0, 1, 0, 0), "OTTO".toByteArray(), "ttcf".toByteArray(), "true".toByteArray())
    }
}
