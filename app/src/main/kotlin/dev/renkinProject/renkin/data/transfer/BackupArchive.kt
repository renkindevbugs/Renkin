package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object BackupArchiveLimits {
    const val MAX_ENTRIES = 10_000
    const val MAX_ARCHIVE_BYTES = 1088L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    // Transport cap only: the JSON tree and decoded models still have to fit in the heap.
    const val MAX_DATA_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 64L * 1024

    fun entryBytes(name: String): Long =
        when (name) {
            "manifest.json" -> MAX_MANIFEST_BYTES
            "data.json" -> MAX_DATA_BYTES
            else -> if (name.startsWith("${IconModifierInputStore.ARCHIVE_DIR}/")) IconModifierInputStore.MAX_ARTWORK_BYTES else MAX_ENTRY_BYTES
        }

    fun validateName(name: String) {
        val path = name.removeSuffix("/")
        if (name.length > 1024 || path.isEmpty() || name.startsWith('/') ||
            name.any { it == '\\' || it == ':' || it.isISOControl() } ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) throw IOException("Unsafe archive entry name")
    }
}

internal class StagedBackupArchive private constructor(
    private val directory: File,
    val entries: Map<String, File>,
    val fingerprint: String
) : Closeable {
    override fun close() {
        BackupStaging.release(directory)
    }

    companion object {
        suspend fun read(stagingRoot: File, open: () -> InputStream): StagedBackupArchive {
            val directory = BackupStaging.create(stagingRoot, "backup-import-")
            try {
                ensureBackupSpace(directory)
                // ZipFile requires the central directory; a truncated ZIP must not be accepted
                // just because all of its local entry headers happened to be readable.
                val archive = File(directory, "archive.zip")
                val digest = MessageDigest.getInstance("SHA-256")
                open().use { input ->
                    archive.outputStream().use { output ->
                        copyBounded(input, output, BackupArchiveLimits.MAX_ARCHIVE_BYTES, digest = digest, spaceDirectory = directory)
                    }
                }
                val files = linkedMapOf<String, File>()
                val names = mutableSetOf<String>()
                var totalBytes = 0L
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        currentCoroutineContext().ensureActive()
                        val entry = entries.nextElement()
                        BackupArchiveLimits.validateName(entry.name)
                        if (!names.add(entry.name.removeSuffix("/"))) {
                            throw IOException("Duplicate archive entry")
                        }
                        if (names.size > BackupArchiveLimits.MAX_ENTRIES) {
                            throw BackupSizeException()
                        }
                        val limit = minOf(
                            BackupArchiveLimits.entryBytes(entry.name),
                            BackupArchiveLimits.MAX_TOTAL_BYTES - totalBytes
                        )
                        if (entry.size < 0 || entry.size > limit) {
                            throw BackupSizeException()
                        }
                        ensureBackupSpace(directory, entry.size)
                        val file = File(directory, "entry-${names.size}")
                        val crc = CRC32()
                        val bytes = zip.getInputStream(entry).use { input ->
                            file.outputStream().use { output -> copyBounded(input, output, limit, crc, spaceDirectory = directory) }
                        }
                        if (bytes != entry.size || crc.value != entry.crc) {
                            throw IOException("Corrupt archive entry")
                        }
                        totalBytes += bytes
                        if (entry.isDirectory) {
                            if (bytes != 0L) throw IOException("Archive directory contains data")
                            file.delete()
                        } else files[entry.name] = file
                    }
                }
                archive.delete()
                return StagedBackupArchive(directory, files, digest.digest().joinToString("") { "%02x".format(it) })
            } catch (error: Throwable) {
                if (error is OutOfMemoryError) Log.error("BackupArchive", "Out of memory reading archive; sourceBytes=${File(directory, "archive.zip").length()}, stagedBytes=${directory.listFiles()?.sumOf { it.length() }}", error)
                BackupStaging.release(directory)
                throw error
            }
        }
    }
}

internal suspend fun writeBackupArchive(
    open: () -> OutputStream,
    manifest: String,
    data: File,
    files: Map<String, File> = emptyMap()
) {
    val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
    val entries = linkedMapOf("data.json" to data).apply { putAll(files) }
    if (entries.size != files.size + 1 || "manifest.json" in entries) throw IOException("Duplicate export entry")
    if (entries.size + 1 > BackupArchiveLimits.MAX_ENTRIES || manifestBytes.size > BackupArchiveLimits.MAX_MANIFEST_BYTES) {
        throw BackupSizeException()
    }
    var totalBytes = manifestBytes.size.toLong()
    for ((name, file) in entries) {
        BackupArchiveLimits.validateName(name)
        if (!file.isFile) throw IOException("Invalid backup file entry")
        if (file.length() > BackupArchiveLimits.entryBytes(name)) throw BackupSizeException()
        totalBytes += file.length()
    }
    if (totalBytes > BackupArchiveLimits.MAX_TOTAL_BYTES) throw BackupSizeException()
    currentCoroutineContext().ensureActive()
    ZipOutputStream(open().buffered()).use { zip ->
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(manifestBytes)
        zip.closeEntry()
        var written = manifestBytes.size.toLong()
        for ((name, file) in entries) {
            currentCoroutineContext().ensureActive()
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { input ->
                written += copyBounded(input, zip, minOf(
                    BackupArchiveLimits.entryBytes(name), BackupArchiveLimits.MAX_TOTAL_BYTES - written
                ))
            }
            zip.closeEntry()
        }
    }
}

private suspend fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    crc: CRC32? = null,
    digest: MessageDigest? = null,
    spaceDirectory: File? = null
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytes = 0L
    var nextSpaceCheck = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        if (spaceDirectory != null && bytes >= nextSpaceCheck) {
            ensureBackupSpace(spaceDirectory, 1024 * 1024L)
            nextSpaceCheck = bytes + 1024 * 1024
        }
        val count = input.read(buffer)
        if (count < 0) return bytes
        bytes += count
        if (bytes > limit) throw BackupSizeException()
        output.write(buffer, 0, count)
        crc?.update(buffer, 0, count)
        digest?.update(buffer, 0, count)
    }
}
