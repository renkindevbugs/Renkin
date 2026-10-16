package dev.renkinProject.renkin

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfileStyleStore
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.transfer.AutoBackupManager
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.transfer.BackupMemoryException
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import dev.renkinProject.renkin.data.transfer.BackupSizeException
import dev.renkinProject.renkin.data.transfer.BackupSpaceException
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.di.AppModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class BackupUiCoordinatorTest {
    private val draftInvalidation by lazy { EditorDraftInvalidation(EditorDraftStore(application), UnsavedIconJournalStore(application)) }
    private lateinit var application: Application
    private lateinit var packDb: RenkinPackDatabase
    private lateinit var watchDb: WatchDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var provider: ApplicationProvider
    private lateinit var manager: BackupManager
    private val mutex = Mutex()
    private val notifications = mutableListOf<Int>()
    private val exports = mutableListOf<Pair<ProfileSessionToken, Long?>>()
    private var beforeExport: suspend () -> Unit = {}

    @Before
    fun setUp() = runBlocking {
        StorageOperationGate.resetForTests()
        application = RuntimeEnvironment.getApplication()
        application.dataStore.edit { it.clear() }
        ShadowStatFs.registerStats(application.filesDir, 1_000_000, 1_000_000, 1_000_000)
        packDb = Room.inMemoryDatabaseBuilder(application, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        watchDb = Room.inMemoryDatabaseBuilder(application, WatchDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RenkinPackRepository(packDb)
        repository.replaceEverything(listOf(Profile(id = 1, name = "Default"), Profile(id = 4, name = "Other")), emptyList())
        ProfileStyleStore(repository, application.dataStore).initialize()
        val catalog = AppModule.provideIconPackCatalog(application)
        val icons = AppModule.provideIconPackRepository(application, catalog)
        val locks = AppModule.provideIconLockManager(application, repository)
        val profiles = AppModule.provideProfileManager(application, repository)
        val installedIcons = dev.renkinProject.renkin.packages.InstalledIconLoader(application)
        provider = AppModule.provideApplicationProvider(
            application, AppModule.provideRenkinPackStore(application, repository), repository,
            icons, locks, profiles,
            AppModule.provideIconGenerationService(application, icons, catalog, installedIcons),
            AppModule.provideIconPackBuildService(application, icons, repository, locks, profiles, catalog),
            editorDraftInvalidation = draftInvalidation,
            installedIconLoader = installedIcons
        )
        manager = BackupManager(application, repository, WatchRepository(watchDb), catalog, editorDraftInvalidation = draftInvalidation)
    }

    @After
    fun tearDown() {
        packDb.close()
        watchDb.close()
        StorageOperationGate.resetForTests()
    }

    private suspend fun withCoordinator(action: suspend (BackupUiCoordinator) -> Unit) {
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val coordinator = BackupUiCoordinator(
            provider, manager, AutoBackupManager(application),
            CoroutineScope(currentCoroutineContext() + owner), mutex,
            saveBeforeExport = { token, profileId ->
                assertTrue(mutex.isLocked)
                exports += token to profileId
                beforeExport()
            },
            onRestored = { error("These scenarios must not replace the active session") },
            onVerdictsUpdated = { error("Empty test profiles have no verdicts to update") },
            notify = { notifications += it }
        )
        try { action(coordinator) } finally {
            coordinator.close()
            owner.cancelAndJoin()
            awaitNoStaging()
        }
    }

    private suspend fun BackupUiCoordinator.awaitIdle() = withTimeout(10_000) {
        while (backupInProgress) delay(10)
    }

    private fun stagedFiles(): List<File> = File(application.filesDir, "backup-imports")
        .listFiles()?.toList().orEmpty()

    private suspend fun awaitNoStaging() = withTimeout(10_000) {
        while (stagedFiles().isNotEmpty()) delay(10)
    }

    private suspend fun fullBackup(): Uri {
        val archive = File(application.cacheDir, "full.renkin")
        manager.exportBackup { archive.outputStream() }
        return Uri.fromFile(archive)
    }

    @Test
    fun fullExportSavesUnderMutexAndCopiesOutsideIt() = runBlocking {
        val uri = Uri.parse("content://backup.test/full")
        var wroteOutsideMutex = true
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                wroteOutsideMutex = wroteOutsideMutex && !mutex.isLocked
                super.write(bytes, offset, length)
            }
        }
        shadowOf(application.contentResolver).registerOutputStream(uri, output)
        withCoordinator { coordinator ->
            val expected = provider.sessionToken
            coordinator.exportBackup(uri)
            assertTrue(coordinator.backupInProgress)
            coordinator.awaitIdle()
            assertEquals(listOf(expected to null), exports)
            assertTrue(output.size() > 0)
            assertTrue(wroteOutsideMutex)
            assertEquals(listOf(R.string.backupExported), notifications)
        }
    }

    @Test
    fun profileExportKeepsItsCapturedTargetAndRejectsDuplicateTap() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeExport = { entered.complete(Unit); release.await() }
        withCoordinator { coordinator ->
            val archive = File(application.cacheDir, "shared.renkin")
            coordinator.exportProfile(4, Uri.fromFile(archive))
            try {
                entered.await()
                coordinator.exportProfile(1, Uri.fromFile(File(application.cacheDir, "duplicate.renkin")))
                assertEquals(listOf(provider.sessionToken to 4L), exports)
            } finally { release.complete(Unit) }
            coordinator.awaitIdle()
            assertEquals(listOf(R.string.profileExported), notifications)
            val prepared = manager.prepareImport(Uri.fromFile(archive))
            try { assertEquals(BackupManager.ImportKind.PROFILE, prepared.inspection.kind) }
            finally { manager.discardImport(prepared) }
            assertFalse(File(application.cacheDir, "duplicate.renkin").exists())
        }
    }

    @Test
    fun exportFailuresMapToSpecificMessagesAndAllowRetry() = runBlocking {
        withCoordinator { coordinator ->
            val uri = Uri.fromFile(File(application.cacheDir, "failed.renkin"))
            val failures = listOf(
                BackupSpaceException() to R.string.backupInsufficientStorage,
                BackupSizeException() to R.string.backupSizeLimitExceeded,
                BackupMemoryException(OutOfMemoryError("test")) to R.string.backupInsufficientMemory,
                IOException("test") to R.string.backupExportFailed
            )
            for ((failure, message) in failures) {
                beforeExport = { throw failure }
                coordinator.exportBackup(uri)
                coordinator.awaitIdle()
                assertEquals(message, notifications.last())
                assertFalse(mutex.isLocked)
            }
            assertEquals(failures.size, exports.size)
        }
    }

    @Test
    fun closingFullImportPreviewReleasesStagingWithoutChangingSession() = runBlocking {
        val uri = fullBackup()
        withCoordinator { coordinator ->
            val expected = provider.sessionToken
            coordinator.importFile(uri)
            coordinator.awaitIdle()
            assertEquals(listOf("Default", "Other"), coordinator.pendingBackupImport?.preview?.profiles?.map { it.name })
            assertTrue(stagedFiles().isNotEmpty())
            coordinator.cancelBackupImport()
            assertNull(coordinator.pendingBackupImport)
            awaitNoStaging()
            assertEquals(expected, provider.sessionToken)
            assertTrue(notifications.isEmpty())
        }
    }

    @Test
    fun closeDiscardsPendingImportAndRejectsFurtherOperations() = runBlocking {
        val uri = fullBackup()
        withCoordinator { coordinator ->
            coordinator.importFile(uri)
            coordinator.awaitIdle()
            assertNotNull(coordinator.pendingBackupImport)
            coordinator.close()
            coordinator.confirmBackupImport()
            coordinator.importFile(uri)
            coordinator.exportBackup(Uri.fromFile(File(application.cacheDir, "closed.renkin")))
            awaitNoStaging()
            assertNull(coordinator.pendingBackupImport)
            assertFalse(coordinator.backupInProgress)
            assertTrue(exports.isEmpty())
            assertTrue(notifications.isEmpty())
        }
    }

    @Test
    fun closeDuringPreparationDoesNotPublishOrLeakALateImport() = runBlocking {
        val uri = fullBackup()
        withCoordinator { coordinator ->
            val release = CompletableDeferred<Unit>()
            val blocker = launch(start = CoroutineStart.UNDISPATCHED) {
                StorageOperationGate.run { release.await() }
            }
            try {
                coordinator.importFile(uri)
                yield()
                assertTrue(coordinator.backupInProgress)
                coordinator.close()
            } finally { release.complete(Unit); blocker.join() }
            coordinator.awaitIdle()
            assertNull(coordinator.pendingBackupImport)
            assertTrue(notifications.isEmpty())
        }
    }

    @Test
    fun sharedProfileImportIsAdditiveAndPreservesActiveSession() = runBlocking {
        val archive = File(application.cacheDir, "profile.renkin")
        manager.exportProfile(4) { archive.outputStream() }
        withCoordinator { coordinator ->
            val expected = provider.sessionToken
            coordinator.importFile(Uri.fromFile(archive))
            coordinator.awaitIdle()
            assertNull(coordinator.pendingBackupImport)
            assertEquals(expected, provider.sessionToken)
            assertEquals(3, repository.profiles().size)
            assertEquals(listOf(R.string.profileImported), notifications)
        }
    }
}
