package dev.renkinProject.renkin.data.transfer

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dev.renkinProject.renkin.apk.PackKeystore
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.dataStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Reads shared preferences, but never executes the supplied durable commit operation. */
internal class BackupRestoreMemoryProbe(
    private val context: Context,
    private val probe: BackupMemoryDeviceTest
) {
    suspend fun run() {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "perf6e-restore-").toFile().canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile)
        val scoped = RestoreContext(context, root)
        // The app has already initialized the process-wide delegate. It is read-only here.
        check(scoped.dataStore === context.dataStore)
        val packDb = RenkinPackDatabase.open(scoped, "renkinPack")
        val watchDb = WatchDatabase.open(scoped, "watch")
        try {
            assertEquals(scoped.getDatabasePath("renkinPack").canonicalPath,
                File(requireNotNull(packDb.openHelper.writableDatabase.path)).canonicalPath)
            assertEquals(scoped.getDatabasePath("watch").canonicalPath,
                File(requireNotNull(watchDb.openHelper.writableDatabase.path)).canonicalPath)
            val pack = RenkinPackRepository(packDb)
            val watch = WatchRepository(watchDb)
            val seed = File(root, "seed").apply { check(mkdir()) }
            val livePack = RenkinPackRepository(context)
            val liveWatch = WatchRepository(context)
            StorageOperationGate.run {
                livePack.writeRecoverySnapshot(File(seed, "pack.json"))
                liveWatch.writeRecoverySnapshot(File(seed, "watch.json"))
                pack.restoreRecoverySnapshot(File(seed, "pack.json"))
                watch.restoreRecoverySnapshot(File(seed, "watch.json"))
                copyTree(UploadedImageStore.directoryPath(context), UploadedImageStore.directoryPath(scoped))
                copyTree(IconModifierInputStore.directory(context), IconModifierInputStore.directory(scoped))
                for (name in listOf(PackKeystore.FILE_NAME, PackKeystore.PASSWORD_FILE_NAME)) {
                    copyTree(File(context.filesDir, name), File(scoped.filesDir, name))
                }
            }
            val manager = BackupManager(scoped, pack, watch,
                editorDraftInvalidation = EditorDraftInvalidation(EditorDraftStore(scoped), UnsavedIconJournalStore(scoped)))
            val archive = File(scoped.cacheDir, "fixture.renkin")
            manager.exportBackup { archive.outputStream() }
            val archiveDigest = digest(archive)
            val cloneBaseline = snapshots(pack, watch, File(root, "clone-baseline"))
            val liveBaseline = StorageOperationGate.run {
                snapshots(livePack, liveWatch, File(root, "live-baseline"))
            }
            val realFiles = persistedFiles()
            val preferences = context.dataStore.getPreferencesAfterPendingWrites()
            val epoch = StorageOperationGate.epoch
            probe.emit(JSONObject().put("phase", "restore-fixture")
                .put("zipBytes", archive.length())
                .put("jsonBytes", ZipFile(archive).use { it.getEntry("data.json").size })
                .put("isolatedDatabasePaths", true)
                .put("sharedPreferencesReadOnly", true))
            repeat(3) { trial ->
                val prefix = "restore-${trial + 1}"
                val prepared = probe.measured("$prefix-inspection") { manager.prepareImport(Uri.fromFile(archive)) }
                try {
                    assertEquals(BackupManager.ImportKind.BACKUP, prepared.inspection.kind)
                    assertEquals(archiveDigest, prepared.inspection.fingerprint)
                    var reached = false
                    val outcome = probe.measured("$prefix-to-boundary") {
                        coroutineScope {
                            val stages = launch {
                                combine(manager.restoreStage, manager.snapshotStage) { restore, snapshot ->
                                    restore.name to snapshot?.name
                                }.collect { (restore, snapshot) ->
                                    probe.emit(JSONObject().put("phase", "$prefix-stage")
                                        .put("restoreStage", restore).put("snapshotStage", snapshot)
                                        .put("heapUsedBytes", heapUsed()))
                                }
                            }
                            try {
                                runCatching {
                                    manager.importPrepared(prepared) {
                                        reached = true
                                        val recovery = File(scoped.filesDir, "backup-recovery")
                                        probe.emit(JSONObject().put("phase", "$prefix-boundary")
                                            .put("heapUsedBytes", heapUsed())
                                            .put("snapshotBytes", bytes(recovery))
                                            .put("packSnapshotBytes", recovery.walkTopDown()
                                                .filter { it.isFile && it.name == "pack.json" }.sumOf { it.length() })
                                            .put("fileBatchBytes", scoped.filesDir.listFiles().orEmpty()
                                                .filter { it.name.startsWith("backup-file-commit-") }.sumOf(::bytes)))
                                        throw RestoreBoundaryReached()
                                    }
                                }
                            } finally { stages.cancelAndJoin() }
                        }
                    }
                    assertTrue(reached)
                    assertTrue(outcome.exceptionOrNull() is RestoreBoundaryReached)
                    assertEquals(BackupManager.RestoreStage.IDLE, manager.restoreStage.value)
                    assertFalse(manager.restoreCancellable.value)
                    assertEquals(epoch, StorageOperationGate.epoch)
                    assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
                    assertEquals(cloneBaseline, snapshots(pack, watch, File(root, "clone-check")))
                    StorageOperationGate.run {
                        assertEquals(liveBaseline, snapshots(livePack, liveWatch, File(root, "live-check")))
                        assertTrue("Original persisted files changed", realFiles == persistedFiles())
                        assertTrue("Original preferences changed", preferences == context.dataStore.getPreferencesAfterPendingWrites())
                    }
                    assertEquals(archiveDigest, digest(archive))
                    assertTrue(prepared.staged.entries.values.none { it.exists() })
                    assertTrue(File(scoped.filesDir, "backup-imports").listFiles().isNullOrEmpty())
                    assertTrue(File(scoped.filesDir, "backup-recovery").listFiles().isNullOrEmpty())
                    assertTrue(scoped.filesDir.listFiles().orEmpty().none { it.name.startsWith("backup-file-commit-") })
                    probe.emit(JSONObject().put("phase", "$prefix-verified")
                        .put("profiles", prepared.inspection.backup?.profiles?.size)
                        .put("icons", prepared.inspection.backup?.iconCount)
                        .put("commitExecuted", false).put("cloneDataUnchanged", true)
                        .put("liveDataUnchanged", true).put("stagingReleased", true))
                } finally { manager.discardImport(prepared) }
                delay(500)
            }
        } finally {
            packDb.close()
            watchDb.close()
            check(root.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("perf6e-restore-"))
            check(root.deleteRecursively())
        }
        assertFalse(root.exists())
        probe.emit(JSONObject().put("phase", "restore-scope-released"))
    }

    private suspend fun snapshots(pack: RenkinPackRepository, watch: WatchRepository, directory: File): List<String> {
        check(directory.isDirectory || directory.mkdir())
        val packFile = File(directory, "pack.json")
        val watchFile = File(directory, "watch.json")
        pack.writeRecoverySnapshot(packFile)
        watch.writeRecoverySnapshot(watchFile)
        return listOf(digest(packFile), digest(watchFile))
    }

    private fun persistedFiles(): Map<String, String> = buildMap {
        for ((label, directory) in listOf("files" to context.filesDir, "no_backup" to context.noBackupFilesDir)) {
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                put("$label/${file.relativeTo(directory).path}", "${file.length()}:${file.lastModified()}:${digest(file)}")
            }
        }
    }

    private fun copyTree(source: File, target: File) {
        if (!source.exists()) return
        check(!Files.isSymbolicLink(source.toPath()) &&
            source.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())) {
            "Source escaped app file storage"
        }
        if (source.isDirectory) {
            check(target.isDirectory || target.mkdirs())
            source.listFiles().orEmpty().forEach { copyTree(it, File(target, it.name)) }
        } else {
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            source.inputStream().use { input -> FileOutputStream(target).use { output ->
                input.copyTo(output, 8192)
                output.fd.sync()
            } }
            check(target.setLastModified(source.lastModified()))
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

    private fun bytes(directory: File) = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun heapUsed() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private class RestoreBoundaryReached : IOException("Diagnostic stop before durable restore commit")

    private class RestoreContext(base: Context, root: File) : ContextWrapper(base) {
        private val ownedRoot = root.canonicalFile
        private val databases = File(ownedRoot, "databases").apply { check(mkdir()) }
        private val files = File(ownedRoot, "files").apply { check(mkdir()) }
        private val cache = File(ownedRoot, "cache").apply { check(mkdir()) }
        private val noBackup = File(ownedRoot, "no_backup").apply { check(mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getDatabasePath(name: String): File {
            val path = (if (File(name).isAbsolute) File(name) else File(databases, name)).canonicalFile
            check(path.parentFile == databases) { "Database path escaped diagnostic scope" }
            return path
        }
        override fun databaseList(): Array<String> = databases.list().orEmpty().map { it }.toTypedArray()
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory, null)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
            val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
                (if (mode and MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
            return SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, flags, errorHandler)
        }
    }
}
