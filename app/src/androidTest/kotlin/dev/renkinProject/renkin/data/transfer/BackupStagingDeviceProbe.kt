package dev.renkinProject.renkin.data.transfer

import android.content.Context
import android.util.JsonWriter
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.dataStore
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.delay
import org.json.JSONObject

internal class BackupStagingDeviceProbe(
    private val context: Context,
    private val probe: BackupMemoryDeviceTest,
    private val manager: BackupManager
) {
    private data class Expected(val metadata: BackupData, val count: Int, val hashes: Map<Long, String>)

    suspend fun run(compareFull: Boolean = true) {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "perf6f-staging-").toFile().canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile)
        try {
            val fixture = File(root, "fixture.renkin")
            manager.exportBackup { fixture.outputStream() }
            val pack = RenkinPackRepository(context)
            val watch = WatchRepository(context)
            val packFile = File(root, "pack.json")
            val watchFile = File(root, "watch.json")
            suspend fun stores() = StorageOperationGate.run {
                pack.writeRecoverySnapshot(packFile)
                watch.writeRecoverySnapshot(watchFile)
                listOf(digest(packFile), digest(watchFile))
            }
            val baseline = stores()
            val preferences = context.dataStore.getPreferencesAfterPendingWrites()
            val epoch = StorageOperationGate.epoch
            val realFiles = persistedFiles()
            StagedBackupArchive.read(root) { fixture.inputStream() }.use { archive ->
                val dataFile = requireNotNull(archive.entries["data.json"])
                val available = archive.entries.filterKeys { it.startsWith("${IconModifierInputStore.ARCHIVE_DIR}/") }
                    .mapKeys { it.key.removePrefix("${IconModifierInputStore.ARCHIVE_DIR}/") }
                val archiveDigest = digest(fixture)
                val dataDigest = digest(dataFile)
                probe.emit(JSONObject().put("phase", "row-staging-fixture")
                    .put("zipBytes", fixture.length()).put("jsonBytes", dataFile.length())
                    .put("compareFullDecoder", compareFull))
                val fromStore = if (compareFull) null else storeBaseline(pack, dataFile, available)
                repeat(3) { index ->
                    val prefix = "row-staging-${index + 1}"
                    val expected = fromStore ?: baseline(dataFile, available, prefix)
                    val staged = probe.measured("$prefix-prepare") {
                        StagedBackupData.read(root, dataFile, BackupCodec.FORMAT_VERSION,
                            BackupManager.ImportKind.BACKUP, available)
                    }
                    staged.use {
                        check(expected.metadata == it.metadata) { "Staged metadata differs from full decoder" }
                        check(expected.count == it.iconCount)
                        check(it.metadata.profiles.all { profile -> profile.icons.isEmpty() })
                        val hashes = probe.measured("$prefix-replay") {
                            it.metadata.profiles.associate { profile ->
                                profile.profile.id to hashRows { consume -> it.visit(profile.profile.id, consume) }
                            }
                        }
                        check(expected.hashes == hashes) { "Staged rows differ from full decoder" }
                        probe.emit(JSONObject().put("phase", "$prefix-rows")
                            .put("profiles", it.metadata.profiles.size).put("icons", it.iconCount)
                            .put("rowFileBytes", it.directory.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() })
                            .put("artworkInMetadata", false).put("allPortableFieldsMatch", true))
                    }
                    check(!staged.directory.exists())
                    check(digest(dataFile) == dataDigest && digest(fixture) == archiveDigest)
                    check(stores() == baseline) { "Live databases changed during staging" }
                    StorageOperationGate.run {
                        check(persistedFiles() == realFiles) { "Live files changed during staging" }
                        check(context.dataStore.getPreferencesAfterPendingWrites() == preferences) { "Live preferences changed" }
                    }
                    check(StorageOperationGate.epoch == epoch)
                    check(StorageOperationGate.recoveryState.value == StorageRecoveryState.READY)
                    probe.emit(JSONObject().put("phase", "$prefix-verified")
                        .put("liveDataUnchanged", true).put("stagingReleased", true))
                    delay(500)
                }
            }
        } finally {
            check(root.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("perf6f-staging-"))
            check(root.deleteRecursively())
        }
        probe.emit(JSONObject().put("phase", "row-staging-scope-released"))
    }

    // Returning only hashes/metadata keeps the full baseline DTO out of the staging owner.
    private suspend fun baseline(dataFile: File, available: Map<String, File>, prefix: String): Expected {
        val data = probe.measured("$prefix-full-decoder") {
            val decoded = BackupValidator.prepare(
                readBackupData(dataFile.reader(Charsets.UTF_8), BackupCodec.FORMAT_VERSION),
                BackupManager.ImportKind.BACKUP, BackupCodec.FORMAT_VERSION)
            val inputs = BackupModifierInputPreparation(available)
            decoded.copy(profiles = decoded.profiles.map { profile ->
                profile.copy(icons = profile.icons.map { inputs.prepare(it) })
            })
        }
        val hashes = data.profiles.associate { profile ->
            profile.profile.id to hashRows { consume -> profile.icons.forEach { consume(it) } }
        }
        return Expected(data.copy(profiles = data.profiles.map { it.copy(icons = emptyList()) }),
            data.profiles.sumOf { it.icons.size }, hashes)
    }

    private suspend fun storeBaseline(pack: RenkinPackRepository, dataFile: File, available: Map<String, File>): Expected =
        StorageOperationGate.run {
            val inspected = BackupValidator.prepare(
                readBackupInspectionData(dataFile.reader(Charsets.UTF_8), BackupCodec.FORMAT_VERSION),
                BackupManager.ImportKind.BACKUP, BackupCodec.FORMAT_VERSION)
            val inputs = BackupModifierInputPreparation(available)
            val hashes = pack.readExportIcons { rows ->
                inspected.profiles.associate { profile ->
                    profile.profile.id to hashRows { consume ->
                        rows.visit(profile.profile.id) { consume(inputs.prepare(it)) }
                    }
                }
            }
            Expected(inspected.copy(profiles = inspected.profiles.map { it.copy(icons = emptyList()) }),
                inspected.profiles.sumOf { it.icons.size }, hashes)
        }

    private suspend fun hashRows(visit: suspend (suspend (DbApplication) -> Unit) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()) }
            override fun write(buffer: ByteArray, offset: Int, count: Int) { digest.update(buffer, offset, count) }
        }
        val writer = JsonWriter(sink.writer(Charsets.UTF_8).buffered())
        writer.beginArray()
        visit { writeBackupIcon(writer, it) }
        writer.endArray()
        writer.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun persistedFiles(): Map<String, String> = buildMap {
        for ((label, directory) in listOf("files" to context.filesDir, "no_backup" to context.noBackupFilesDir)) {
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                put("$label/${file.relativeTo(directory).path}", "${file.length()}:${file.lastModified()}:${digest(file)}")
            }
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
