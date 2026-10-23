package dev.renkinProject.renkin.data.transfer

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.apk.PackKeystore
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.StorageRecoverySnapshotException
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.syncDirectory
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.UUID

enum class BackupSnapshotStage { ICONS, WATCH_RULES, SETTINGS, FILES, VERIFYING }

// Local prefs.json has no portable manifest and keeps its existing preference-only format.
internal const val RECOVERY_PREFS_FORMAT = BackupCodec.LEGACY_FORMAT_VERSION

internal class BackupRecoveryStore(private val context: Context) {
    private val root get() = File(context.filesDir, "backup-recovery")
    private val pending get() = AtomicFile(File(root, "pending.json"))
    private val previous get() = AtomicFile(File(root, "previous.json"))

    data class Snapshot(val directory: File, val files: List<String>, val fileBatch: String? = null)

    fun pendingSnapshot(): Snapshot? = readPointer(pending)?.let(::readSnapshot)
    fun previousSnapshot(): Snapshot? = readPointer(previous)?.let(::readSnapshot)

    fun hasPreviousState(): Boolean = hasPointer(previous)

    fun discardPreviousState() {
        if (hasPointer(pending)) throw IOException("Recovery is still pending")
        previous.delete()
        if (hasPointer(previous)) throw IOException("Cannot discard previous saved state")
        syncDirectory(root)
        cleanup()
    }

    fun preserveAbandonedRecovery() {
        if (root.exists()) {
            val retained = File(context.filesDir, "backup-recovery-abandoned-${UUID.randomUUID()}")
            // Preserve even a corrupt pointer/manifest. It must not be parsed or collected.
            Os.rename(root.absolutePath, retained.absolutePath)
            Log.info("BackupRecoveryStore", "Recovery abandoned by user; retained at ${retained.absolutePath}")
        }
        syncDirectory(context.filesDir)
    }

    fun hasAbandonedRecovery(): Boolean = abandonedDirectories().isNotEmpty()

    suspend fun preserveCurrentProfileStyles(pack: RenkinPackRepository) {
        val retained = File(context.filesDir, "backup-recovery-abandoned-${UUID.randomUUID()}")
        if (!retained.mkdirs()) throw IOException("Cannot preserve damaged profile styles")
        // Keep the exact original documents and their legacy copies before explicit repair.
        pack.writeProfileStyleDiagnostics(File(retained, "profiles.json"))
        syncDirectory(retained)
        syncDirectory(context.filesDir)
    }

    fun discardAbandonedRecovery() {
        for (directory in abandonedDirectories()) {
            deleteAbandonedTree(directory)
            syncDirectory(context.filesDir)
        }
    }

    private fun abandonedDirectories(): List<File> =
        (context.filesDir.listFiles() ?: throw IOException("Cannot list recovery storage")).filter {
            it.isDirectory && it.name.startsWith("backup-recovery-abandoned-") &&
                validId(it.name.removePrefix("backup-recovery-abandoned-"))
        }

    private fun deleteAbandonedTree(file: File) {
        val expected = File(file.parentFile!!.canonicalFile, file.name)
        // Delete links themselves; never follow them into current or external app data.
        if (file.canonicalFile == expected && file.isDirectory) {
            for (child in file.listFiles() ?: throw IOException("Cannot list abandoned recovery files")) {
                deleteAbandonedTree(child)
            }
        }
        if (!file.delete()) throw IOException("Cannot delete abandoned recovery files")
    }

    suspend fun capture(
        pack: RenkinPackRepository,
        watch: WatchRepository,
        onProgress: (BackupSnapshotStage) -> Unit = {}
    ): Snapshot {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create recovery storage")
        syncDirectory(context.filesDir)
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("Cannot create recovery snapshot")
        val started = android.os.SystemClock.elapsedRealtime()
        fun report(stage: BackupSnapshotStage) {
            if (BuildConfig.DEBUG) {
                Log.debug("BackupRecoveryStore", "Snapshot stage=$stage elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            }
            onProgress(stage)
        }
        try {
            ensureBackupSpace(root)
            report(BackupSnapshotStage.ICONS)
            pack.writeRecoverySnapshot(File(directory, "pack.json"))
            report(BackupSnapshotStage.WATCH_RULES)
            watch.writeRecoverySnapshot(File(directory, "watch.json"))
            report(BackupSnapshotStage.SETTINGS)
            val prefs = context.dataStore.getPreferencesAfterPendingWrites().asMap().map { (key, value) ->
                key.name to (BackupPref.of(value) ?: throw IOException("Unsupported local recovery preference type"))
            }.toMap()
            FileOutputStream(File(directory, "prefs.json")).use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                writeBackupData(BackupData(emptyList(), prefs), writer, RECOVERY_PREFS_FORMAT)
                writer.flush()
                output.fd.sync()
            }
            report(BackupSnapshotStage.FILES)
            val gallery = UploadedImageStore.directory(context)
            gallery.walkTopDown().filter { it.isFile }.forEach { source ->
                val relative = source.relativeTo(gallery).invariantSeparatorsPath
                copySynced(source, File(directory, "gallery/$relative"))
            }
            val inputHashes = pack.modifierInputDocuments().mapNotNull { StoredIconModifierInput.artwork(it)?.hash }.toSet()
            for (hash in inputHashes) {
                val source = IconModifierInputStore.file(context, hash)
                if (source.isFile) {
                    copySynced(source, File(directory, "${IconModifierInputStore.ARCHIVE_DIR}/${source.name}"))
                }
            }
            for (name in listOf(PackKeystore.FILE_NAME, PackKeystore.PASSWORD_FILE_NAME)) {
                context.filesDir.resolve(name).takeIf { it.isFile }?.let { copySynced(it, File(directory, "signing/$name")) }
            }
            report(BackupSnapshotStage.VERIFYING)
            val files = mutableListOf<JSONObject>()
            for (file in directory.walkTopDown()) {
                if (!file.isFile) continue
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                files += JSONObject().put("path", relative).put("size", file.length()).put("sha256", fingerprint(file))
            }
            val metadata = JSONObject().put("version", 1).put("createdAt", System.currentTimeMillis())
                .put("files", JSONArray(files))
            directory.walkBottomUp().filter { it.isDirectory }.forEach(::syncDirectory)
            writeAtomic(AtomicFile(File(directory, "snapshot.json")), metadata)
            syncDirectory(directory)
            syncDirectory(root)
            Log.info("BackupRecoveryStore", "Snapshot saved elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            return Snapshot(directory, files.map { it.getString("path") })
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun activate(snapshot: Snapshot) {
        if (hasPointer(pending)) throw IOException("An interrupted restore still requires recovery")
        writeAtomic(pending, JSONObject().put("id", snapshot.directory.name))
        syncDirectory(root)
    }

    fun attachFileBatch(snapshot: Snapshot, batchName: String): Snapshot {
        if (!batchName.startsWith("backup-file-commit-") || !validId(batchName.removePrefix("backup-file-commit-"))) {
            throw IOException("Invalid recovery file batch")
        }
        val file = File(snapshot.directory, "snapshot.json")
        val metadata = parseBackupJson(file.readText(Charsets.UTF_8)).put("fileBatch", batchName)
        writeAtomic(AtomicFile(file), metadata)
        syncDirectory(snapshot.directory)
        return snapshot.copy(fileBatch = batchName)
    }

    fun ensurePending(snapshot: Snapshot) {
        val active = readPointer(pending)
        if (active != null && active != snapshot.directory.name) throw IOException("Recovery ownership changed")
        if (active == null) writeAtomic(pending, JSONObject().put("id", snapshot.directory.name))
        syncDirectory(root)
    }

    fun complete(snapshot: Snapshot) {
        writeAtomic(previous, JSONObject().put("id", snapshot.directory.name))
        syncDirectory(root)
        clearPending()
        cleanup()
    }

    fun clearPending() {
        pending.delete()
        if (hasPointer(pending)) throw IOException("Cannot finish storage recovery")
        syncDirectory(root)
    }

    fun syncMetadata() {
        if (root.isDirectory) syncDirectory(root)
    }

    fun discard(snapshot: Snapshot) {
        // A failed directory sync can make pointer deletion appear complete without being
        // durable. Keep the snapshot even when the visible pointer is temporarily absent.
        if (StorageOperationGate.recoveryState.value == StorageRecoveryState.FAILED) return
        try {
            if (readPointer(pending) != snapshot.directory.name && readPointer(previous) != snapshot.directory.name) {
                cleanupBatch(snapshot)
                if (!snapshot.directory.deleteRecursively()) Log.error("BackupRecoveryStore", "Cannot remove unused recovery snapshot")
            }
        } catch (error: Exception) {
            Log.error("BackupRecoveryStore", "Preserved snapshot because recovery ownership could not be checked", error)
        }
    }

    fun cleanup() {
        try {
            val active = readPointer(pending)
            val retained = setOfNotNull(active, readPointer(previous))
            root.listFiles { file -> file.isDirectory && validId(file.name) }?.forEach { directory ->
                if (directory.name != active && File(directory, "snapshot.json").isFile) {
                    try {
                        cleanupBatch(readSnapshot(directory.name))
                    } catch (error: Exception) {
                        // A discarded snapshot can have unreadable metadata. Its own directory
                        // is still unowned; external batches need verifiable ownership to delete.
                        Log.error("BackupRecoveryStore", "Cannot identify snapshot file batch; external originals preserved", error)
                    }
                }
                if (directory.name !in retained && !directory.deleteRecursively()) {
                    Log.error("BackupRecoveryStore", "Cannot remove abandoned recovery snapshot")
                }
            }
        } catch (error: Exception) {
            Log.error("BackupRecoveryStore", "Preserved recovery storage because cleanup could not verify ownership", error)
        }
    }

    private fun cleanupBatch(snapshot: Snapshot) {
        snapshot.fileBatch?.let { name ->
            val directory = File(context.filesDir, name)
            if (!BackupStaging.isActive(directory) && directory.exists() && !directory.deleteRecursively()) {
                Log.error("BackupRecoveryStore", "Cannot remove completed recovery file batch")
            }
        }
    }

    suspend fun validate(snapshot: Snapshot) {
        val text = recoveryMetadataText(File(snapshot.directory, "snapshot.json"))
        val entries = decodeMetadata { parseBackupJson(text).getJSONArray("files") }
        for (index in 0 until entries.length()) {
            currentCoroutineContext().ensureActive()
            val (path, size, hash) = decodeMetadata {
                val entry = entries.getJSONObject(index)
                Triple(entry.getString("path"), entry.getLong("size"), entry.getString("sha256"))
            }
            val file = File(snapshot.directory, path)
            if (!file.isFile || file.length() != size || fingerprint(file) != hash) {
                throw StorageRecoverySnapshotException("Recovery snapshot is incomplete or corrupt")
            }
        }
    }

    suspend fun restoreFiles(snapshot: Snapshot) {
        UploadedImageStore.invalidatePendingTrash()
        val gallery = UploadedImageStore.directory(context)
        val wanted = snapshot.files.filter { it.startsWith("gallery/") }.map { it.removePrefix("gallery/") }.toSet()
        for (relative in wanted) copyReplacing(File(snapshot.directory, "gallery/$relative"), File(gallery, relative))
        gallery.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.relativeTo(gallery).invariantSeparatorsPath !in wanted && !file.delete()) {
                throw IOException("Cannot remove gallery file during recovery")
            }
        }
        val inputs = IconModifierInputStore.directory(context)
        val wantedInputs = snapshot.files.filter { it.startsWith("${IconModifierInputStore.ARCHIVE_DIR}/") }
            .map { it.removePrefix("${IconModifierInputStore.ARCHIVE_DIR}/") }.toSet()
        for (name in wantedInputs) copyReplacing(
            File(snapshot.directory, "${IconModifierInputStore.ARCHIVE_DIR}/$name"), File(inputs, name)
        )
        for (file in inputs.listFiles().orEmpty()) {
            if (file.isFile && IconModifierInputStore.validName(file.name) && file.name !in wantedInputs && !file.delete()) {
                throw IOException("Cannot remove modifier input during recovery")
            }
        }
        if (inputs.isDirectory) syncDirectory(inputs)
        for (name in listOf(PackKeystore.FILE_NAME, PackKeystore.PASSWORD_FILE_NAME)) {
            val source = File(snapshot.directory, "signing/$name")
            val target = File(context.filesDir, name)
            if (source.isFile) copyReplacing(source, target)
            else if (target.exists() && !target.delete()) throw IOException("Cannot remove signing file during recovery")
        }
        syncDirectory(gallery)
        syncDirectory(context.filesDir)
    }

    private suspend fun copyReplacing(source: File, target: File) {
        val temporary = File(target.parentFile, ".${target.name}.recovery-tmp")
        try {
            copySynced(source, temporary)
            Os.rename(temporary.absolutePath, target.absolutePath)
            syncDirectory(target.parentFile!!)
        } finally { temporary.delete() }
    }

    private suspend fun copySynced(source: File, target: File) {
        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw IOException("Cannot create recovery directory")
        ensureBackupSpace(root, source.length())
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                if (!target.setLastModified(source.lastModified())) throw IOException("Cannot preserve recovery file timestamp")
                output.fd.sync()
            }
        }
        syncDirectory(target.parentFile!!)
    }

    private suspend fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toLowerHex()
    }

    private fun readPointer(pointer: AtomicFile): String? {
        if (!hasPointer(pointer)) return null
        val text = pointer.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val id = decodeMetadata { parseBackupJson(text).getString("id") }
        if (!validId(id)) throw StorageRecoverySnapshotException("Invalid recovery identity")
        return id
    }

    private fun readSnapshot(id: String): Snapshot {
        val directory = File(root, id)
        val text = recoveryMetadataText(File(directory, "snapshot.json"))
        return decodeMetadata {
            val metadata = parseBackupJson(text)
            if (metadata.getInt("version") != 1) throw StorageRecoverySnapshotException("Unsupported recovery snapshot")
            val entries = metadata.getJSONArray("files")
            val files = (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }
            files.forEach { name ->
                BackupArchiveLimits.validateName(name)
                if (name !in listOf("pack.json", "watch.json", "prefs.json") &&
                    !name.startsWith("gallery/") &&
                    !(name.startsWith("${IconModifierInputStore.ARCHIVE_DIR}/") &&
                        IconModifierInputStore.validName(name.removePrefix("${IconModifierInputStore.ARCHIVE_DIR}/"))) &&
                    name !in listOf("signing/${PackKeystore.FILE_NAME}", "signing/${PackKeystore.PASSWORD_FILE_NAME}")) {
                    throw StorageRecoverySnapshotException("Invalid recovery file")
                }
            }
            if (files.distinct().size != files.size || !files.containsAll(listOf("pack.json", "watch.json", "prefs.json"))) {
                throw StorageRecoverySnapshotException("Incomplete recovery snapshot")
            }
            val batch = metadata.optString("fileBatch").takeIf { it.isNotEmpty() }
            if (batch != null && (!batch.startsWith("backup-file-commit-") || !validId(batch.removePrefix("backup-file-commit-")))) {
                throw StorageRecoverySnapshotException("Invalid recovery batch identity")
            }
            Snapshot(directory, files, batch)
        }
    }

    private fun recoveryMetadataText(file: File): String {
        if (!file.isFile) throw StorageRecoverySnapshotException("Recovery metadata is missing")
        return file.readText(Charsets.UTF_8)
    }

    private inline fun <T> decodeMetadata(decode: () -> T): T = try {
        decode()
    } catch (error: StorageRecoverySnapshotException) {
        throw error
    } catch (error: JSONException) {
        throw StorageRecoverySnapshotException("Invalid recovery metadata", error)
    } catch (error: IOException) {
        throw StorageRecoverySnapshotException("Invalid recovery metadata", error)
    }

    private fun validId(id: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)

    private fun hasPointer(file: AtomicFile): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun writeAtomic(file: AtomicFile, data: JSONObject) {
        val encoded = data.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        var finished = false
        try {
            output.write(encoded)
            output.fd.sync()
            file.finishWrite(output)
            finished = true
            // AtomicFile logs some rename failures instead of throwing. Never activate
            // live writes unless the pointer/manifest was actually finalized.
            if (!file.baseFile.isFile || !file.openRead().use { it.readBytes().contentEquals(encoded) }) {
                throw IOException("Cannot finalize recovery metadata")
            }
        } catch (error: Throwable) {
            if (!finished) file.failWrite(output)
            throw error
        }
    }
}
