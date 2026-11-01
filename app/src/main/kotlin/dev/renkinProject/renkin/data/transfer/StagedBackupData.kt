package dev.renkinProject.renkin.data.transfer

import android.util.JsonWriter
import dev.renkinProject.renkin.data.DbApplication
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Validated metadata and privately owned rows; not yet wired to the live import commit. */
internal class StagedBackupData private constructor(
    internal val directory: File,
    val metadata: BackupData,
    private val rows: Map<Long, RowFile>,
    val modifierInputs: Map<String, File>
) : Closeable {
    private data class RowFile(val file: File, val count: Int)
    private var closed = false
    val iconCount: Int get() = rows.values.sumOf { it.count }

    suspend fun visit(profileId: Long, consume: suspend (DbApplication) -> Unit) {
        check(!closed) { "Staged backup data has expired" }
        if (metadata.profiles.none { it.profile.id == profileId }) throw IOException("Unknown staged profile")
        val rowFile = rows[profileId] ?: return
        var count = 0
        readBackupIconRows(rowFile.file.reader(Charsets.UTF_8), profileId) { icon ->
            BackupValidator.validateIcon(icon, profileId)
            consume(icon)
            count++
        }
        if (count != rowFile.count) throw IOException("Staged icon count changed")
    }

    override fun close() {
        if (!closed) {
            closed = true
            BackupStaging.release(directory)
        }
    }

    companion object {
        // Archive owners still perform ZIP limits/CRC/path validation and retain input files.
        suspend fun read(
            stagingRoot: File, dataFile: File, formatVersion: Int, kind: BackupManager.ImportKind,
            availableModifierInputs: Map<String, File> = emptyMap()
        ): StagedBackupData {
            val directory = BackupStaging.create(stagingRoot, "backup-import-rows-")
            try {
                if (!dataFile.isFile) throw IOException("Missing backup data")
                if (dataFile.length() > BackupArchiveLimits.MAX_DATA_BYTES) throw BackupSizeException()
                ensureBackupSpace(directory)
                val inputs = BackupModifierInputPreparation(
                    if (kind == BackupManager.ImportKind.BACKUP) availableModifierInputs else emptyMap())
                val files = linkedMapOf<Int, RowFile>()
                RowSink(directory, inputs, files).use { sink ->
                    val data = BackupValidator.prepare(
                        readBackupStagingMetadata(dataFile.reader(Charsets.UTF_8), formatVersion, sink), kind, formatVersion)
                    val ownedRows = files.mapKeys { (index, _) -> data.profiles[index].profile.id }
                    val metadata = data.copy(profiles = data.profiles.map { it.copy(icons = emptyList()) })
                    return StagedBackupData(directory, metadata, ownedRows, inputs.files.toMap())
                }
            } catch (error: Throwable) {
                BackupStaging.release(directory)
                throw error
            }
        }
    }

    private class RowSink(
        private val directory: File,
        private val inputs: BackupModifierInputPreparation,
        private val files: MutableMap<Int, RowFile>
    ) : BackupJsonRowSink, Closeable {
        private var currentIndex: Int? = null
        private var output: FileOutputStream? = null
        private var writer: JsonWriter? = null
        private var count = 0

        override suspend fun icon(profileIndex: Int, value: JSONObject): JSONObject {
            val icon = inputs.prepare(BackupCodec.decodeIcon(value, 0))
            BackupValidator.validateIcon(icon, 0)
            if (writer == null) {
                check(currentIndex == null && profileIndex !in files)
                currentIndex = profileIndex
                val file = File(directory, "profile-$profileIndex.json")
                output = FileOutputStream(file)
                val context = currentCoroutineContext()
                val stream = object : OutputStream() {
                    private var bytes = 0L
                    private var nextSpaceCheck = 0L
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        context.ensureActive()
                        if (bytes >= nextSpaceCheck) {
                            ensureBackupSpace(directory, 1024 * 1024L)
                            nextSpaceCheck = bytes + 1024 * 1024L
                        }
                        requireNotNull(output).write(buffer, offset, length)
                        bytes += length
                    }
                }
                writer = JsonWriter(stream.writer(Charsets.UTF_8).buffered()).apply { beginArray() }
                files[profileIndex] = RowFile(file, 0)
            }
            check(currentIndex == profileIndex)
            writeBackupIcon(requireNotNull(writer), icon)
            count++
            // Only identity remains in the metadata tree for the existing whole-file validator.
            return JSONObject().put("packageName", icon.packageName).put("activityName", icon.activityName)
                .put("isAdaptiveIcon", false).put("isXml", false).put("drawable", "")
        }

        override fun endProfile(profileIndex: Int) {
            if (writer == null) return
            check(currentIndex == profileIndex)
            requireNotNull(writer).endArray()
            requireNotNull(writer).flush()
            requireNotNull(output).fd.sync()
            files[profileIndex] = requireNotNull(files[profileIndex]).copy(count = count)
            close()
        }

        override fun close() {
            // Do not finalize a partial array on failure; the owner deletes the whole scope.
            try { output?.close() } finally {
                output = null
                writer = null
                currentIndex = null
                count = 0
            }
        }
    }
}
