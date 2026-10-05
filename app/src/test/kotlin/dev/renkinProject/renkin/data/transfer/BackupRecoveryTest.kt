package dev.renkinProject.renkin.data.transfer

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import dev.renkinProject.renkin.apk.PackKeystore
import dev.renkinProject.renkin.data.*
import dev.renkinProject.renkin.data.watch.*
import dev.renkinProject.renkin.dataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class BackupRecoveryTest {
    private lateinit var context: Context
    private lateinit var packDb: RenkinPackDatabase
    private lateinit var watchDb: WatchDatabase
    private lateinit var pack: RenkinPackRepository
    private lateinit var watch: WatchRepository
    private lateinit var store: BackupRecoveryStore
    private lateinit var manager: BackupManager
    private val pref = stringPreferencesKey("AUTO_BACKUP_TREE_URI")
    private val app = AppComponent("app.one", "Main")

    @Before fun setup() {
        StorageOperationGate.resetForTests()
        context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        packDb = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        watchDb = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
        pack = RenkinPackRepository(packDb)
        watch = WatchRepository(watchDb)
        store = BackupRecoveryStore(context)
        manager = BackupManager(context, pack, watch)
    }

    @After fun cleanup() {
        BackupOsShadow.rejectRename = null
        StorageOperationGate.resetForTests()
        packDb.close()
        watchDb.close()
    }

    private suspend fun seed(name: String): Long = StorageOperationGate.run {
        pack.replaceEverything(listOf(Profile(id = 1, name = name)), listOf(
            DbApplication("app.one", "Main", false, false, "ZGF0YQ==")
        ))
        pack.replaceBuiltIcons(1, listOf(BuiltIcon(1, "app.one", "Main", name)))
        watch.replaceAllRules(emptyList())
        val id = watch.saveRule(null, listOf(app), false, listOf("pack.one"), 1)
        watch.upsertState(WatchState(id, "app.one", "Main", "pack.one", 4, "icon", name, 7))
        context.dataStore.edit { it.clear(); it[pref] = name }
        val gallery = UploadedImageStore.directory(context)
        gallery.resolve("image.png").writeText(name)
        gallery.resolve(".trash").mkdirs()
        gallery.resolve(".trash/removed.png").writeText(name)
        PackKeystore.keystoreFile(context.filesDir).writeText(name)
        PackKeystore.passwordFile(context.filesDir).writeText("password-$name")
        id
    }

    private suspend fun capture() = StorageOperationGate.run { store.capture(pack, watch) }

    private suspend fun assertState(name: String, ruleId: Long) {
        assertEquals(name, pack.profiles().single().name)
        assertEquals(name, pack.builtIcons(1).single().iconHash)
        assertEquals(ruleId, watch.getAllRules().single().rule.id)
        assertEquals(name, watch.getStatesForRule(ruleId).single().lastIconHash)
        assertEquals(name, context.dataStore.getPreferencesAfterPendingWrites()[pref])
        assertEquals(name, UploadedImageStore.directory(context).resolve("image.png").readText())
        assertEquals(name, UploadedImageStore.directory(context).resolve(".trash/removed.png").readText())
        assertEquals(name, PackKeystore.keystoreFile(context.filesDir).readText())
        assertEquals("password-$name", PackKeystore.passwordFile(context.filesDir).readText())
    }

    @Test fun startupRecoversAllStoresBeforeFirstWriterAndRepeatsInterruptedRollback() = runBlocking {
        val originalId = seed("Original")
        val expectedRules = watch.getAllRules()
        val completedId = watch.completeWithSuggestion(originalId, app, listOf(CandidateInput("pack.one", "icon", "hash")))
        val expectedCompleted = watch.getAllRules()
        val expectedCandidates = watch.getCandidates(completedId)
        val snapshot = capture()
        store.activate(snapshot)
        seed("Partial")
        // Simulate process death after the first database was rolled back.
        pack.restoreRecoverySnapshot(snapshot.directory.resolve("pack.json"))
        StorageOperationGate.installRecovery { manager.recoverPendingRestore() }
        StorageOperationGate.run {
            assertEquals("Original", pack.profiles().single().name)
            assertEquals(expectedCompleted, watch.getAllRules())
            assertEquals(expectedCandidates, watch.getCandidates(completedId))
            assertEquals("Original", context.dataStore.getPreferencesAfterPendingWrites()[pref])
            assertEquals("Original", UploadedImageStore.directory(context).resolve("image.png").readText())
            assertEquals("Original", PackKeystore.keystoreFile(context.filesDir).readText())
        }
        assertNull(store.pendingSnapshot())
        assertTrue(expectedRules.isNotEmpty())
        manager.recoverPendingRestore()
        assertEquals(expectedCompleted, watch.getAllRules())
    }

    @Test fun corruptSnapshotBlocksWritesUntilExplicitAbandonAndCanBeDeletedSeparately() = runBlocking {
        seed("Original")
        val snapshot = capture()
        store.activate(snapshot)
        val currentId = seed("Current")
        snapshot.directory.resolve("pack.json").appendText("corrupt")
        StorageOperationGate.installRecovery { manager.recoverPendingRestore() }
        assertTrue(runCatching { StorageOperationGate.run { } }.exceptionOrNull() is StorageRecoverySnapshotException)
        assertEquals(StorageRecoveryFailureReason.CORRUPT_SNAPSHOT, StorageOperationGate.recoveryFailureReason.value)
        assertTrue(runCatching { pack.replaceEverything(emptyList(), emptyList()) }.exceptionOrNull() is StorageRecoveryUnavailableException)
        StorageOperationGate.abandonRecovery { manager.abandonPendingRecovery() }
        assertState("Current", currentId)
        assertTrue(store.hasAbandonedRecovery())
        val abandoned = context.filesDir.listFiles()!!.single { it.name.startsWith("backup-recovery-abandoned-") }
        assertEquals("Original", abandoned.resolve("${snapshot.directory.name}/signing/${PackKeystore.FILE_NAME}").readText())
        val unrelated = context.filesDir.resolve("backup-recovery-abandoned-unrelated").apply { mkdirs() }
        manager.discardAbandonedRecovery()
        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
        assertState("Current", currentId)
    }

    @Test fun watchSchemaMismatchIsDetectedBeforePackDatabaseIsChanged() = runBlocking {
        seed("Original")
        store.activate(capture())
        seed("Current")
        val sql = watchDb.openHelper.writableDatabase
        sql.version = sql.version + 1
        StorageOperationGate.installRecovery { manager.recoverPendingRestore() }
        assertTrue(runCatching { StorageOperationGate.run { } }.exceptionOrNull() is StorageRecoverySchemaException)
        assertEquals(StorageRecoveryFailureReason.SCHEMA_CHANGED, StorageOperationGate.recoveryFailureReason.value)
        packDb.openHelper.writableDatabase.query("SELECT name FROM Profile").use {
            assertTrue(it.moveToFirst()); assertEquals("Current", it.getString(0))
        }
        assertNotNull(store.pendingSnapshot())
    }

    @Test fun previousStateRestoresLocalMetadataAndDevicePreferencesAndSupportsRedo() = runBlocking {
        val original = seed("Original")
        store.complete(capture())
        val current = seed("Current")
        manager.restorePreviousState { it() }
        assertState("Original", original)
        manager.restorePreviousState { it() }
        assertState("Current", current)
        assertNull(store.pendingSnapshot())
        manager.discardPreviousState()
        assertFalse(manager.previousStateStored.value)
    }

    @Test fun failedFileRestoreRollsBackBothDatabasesPreferencesAndFiles() = runBlocking {
        seed("Original")
        store.complete(capture())
        val currentId = seed("Current")
        var injected = false
        BackupOsShadow.rejectRename = { source, _ ->
            if (!injected && source.endsWith(".image.png.recovery-tmp")) { injected = true; true } else false
        }
        assertTrue(runCatching { manager.restorePreviousState { it() } }.isFailure)
        assertTrue(injected)
        assertState("Current", currentId)
        assertNull(store.pendingSnapshot())
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }

    @Test fun repeatedFileFailureRetainsJournalAndRetryRestoresCurrentState() = runBlocking {
        seed("Original")
        store.complete(capture())
        val currentId = seed("Current")
        BackupOsShadow.rejectRename = { source, _ -> source.endsWith(".image.png.recovery-tmp") }
        assertTrue(runCatching { manager.restorePreviousState { it() } }.exceptionOrNull() is BackupRecoveryRequiredException)
        assertEquals(StorageRecoveryState.FAILED, StorageOperationGate.recoveryState.value)
        assertNotNull(store.pendingSnapshot())
        BackupOsShadow.rejectRename = null
        StorageOperationGate.installRecovery { manager.recoverPendingRestore() }
        StorageOperationGate.retryRecovery { }
        assertState("Current", currentId)
        assertNull(store.pendingSnapshot())
    }

    @Test fun incompatiblePreviousStateCanBeDiscardedWithoutChangingCurrentState() = runBlocking {
        seed("Original")
        store.complete(capture())
        val current = seed("Current")
        val sql = packDb.openHelper.writableDatabase
        sql.version = sql.version + 1
        manager.recoverPendingRestore()
        assertTrue(manager.previousStateStored.value)
        assertFalse(manager.previousStateAvailable.value)
        assertTrue(runCatching { manager.restorePreviousState { it() } }.exceptionOrNull() is StorageRecoverySchemaException)
        manager.discardPreviousState()
        assertState("Current", current)
        assertFalse(store.hasPreviousState())
    }

    @Test fun exportTargetWriteAllowsLiveMutationsWhileArchiveRemainsConsistent() = runBlocking {
        seed("Captured")
        val output = ByteArrayOutputStream()
        withTimeout(10_000) {
            manager.exportBackup {
                runBlocking { seed("Changed while uploading") }
                output
            }
        }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        val data = BackupCodec.decode(entries.getValue("data.json").toString(Charsets.UTF_8))
        assertEquals("Captured", data.profiles.single().profile.name)
        assertEquals("Captured", entries.getValue("uploads/image.png").toString(Charsets.UTF_8))
        assertEquals("Captured", entries.getValue("keystore/${PackKeystore.FILE_NAME}").toString(Charsets.UTF_8))
        assertEquals("Changed while uploading", pack.profiles().single().name)
    }

    @Test fun outputFailureAlwaysDiscardsPreparedExport() = runBlocking {
        seed("Captured")
        val prepared = manager.prepareBackupExport()
        assertTrue(prepared.archive.exists())
        assertTrue(runCatching { manager.writePreparedExport(prepared) { throw IOException("SAF failed") } }.isFailure)
        assertTrue(prepared.closed)
        assertFalse(prepared.archive.exists())
        manager.discardExport(prepared)
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }

    @Test fun deletingAbandonedCopiesNeverFollowsLinksIntoLiveData() = runBlocking {
        val current = seed("Current")
        val abandoned = context.filesDir.resolve("backup-recovery-abandoned-${UUID.randomUUID()}").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(abandoned.resolve("live").toPath(), UploadedImageStore.directory(context).toPath())
        store.discardAbandonedRecovery()
        assertFalse(abandoned.exists())
        assertState("Current", current)
    }
}
