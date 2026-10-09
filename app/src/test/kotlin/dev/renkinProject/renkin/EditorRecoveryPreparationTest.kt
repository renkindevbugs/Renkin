package dev.renkinProject.renkin

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.editor.*
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import dev.renkinProject.renkin.di.AppModule
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.EditorRecoveryFixtures
import dev.renkinProject.renkin.ui.EditorRecoveryPreparation
import java.io.File
import java.io.IOException
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRecoveryPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: RenkinPackDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var provider: ApplicationProvider
    private lateinit var context: Context
    private lateinit var store: EditorDraftStore
    private lateinit var app: PackageInfoStruct
    private val vm = EditorSessionViewModel()
    private var beforeFont: (() -> Unit)? = null

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        StorageOperationGate.resetForTests()
        val privateDir = temporary.newFolder("private")
        val cache = temporary.newFolder("cache")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = privateDir
            override fun getCacheDir(): File {
                val action = beforeFont; beforeFont = null; action?.invoke()
                return cache
            }
        }
        context.dataStore.edit { it.clear() }
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        repository = RenkinPackRepository(database)
        val catalog = AppModule.provideIconPackCatalog(context)
        val icons = AppModule.provideIconPackRepository(context, catalog)
        val locks = AppModule.provideIconLockManager(context, repository)
        val profiles = AppModule.provideProfileManager(context, repository)
        provider = AppModule.provideApplicationProvider(context, AppModule.provideRenkinPackStore(context, repository), repository,
            icons, locks, profiles, AppModule.provideIconGenerationService(context, icons, catalog),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog))
        provider.ensureInitialized()
        repository.writeProfileStyles(listOf(dev.renkinProject.renkin.data.ProfileStyleUpdate(provider.sessionToken.profileId,
            dev.renkinProject.renkin.data.ProfileStyleCodec.encode(dev.renkinProject.renkin.data.ProfileStyle()), "")))
        app = EditorRecoveryFixtures.application()
        stage(listOf(app))
        store = EditorDraftStore(context)
    }

    @After fun cleanup() {
        database.close(); Dispatchers.resetMain(); StorageOperationGate.resetForTests()
        BackupOsShadow.beforeDirectorySync = null
    }

    private fun stage(apps: List<PackageInfoStruct>) {
        ApplicationProvider::class.java.getDeclaredField("stagedApplications").apply { isAccessible = true }
            .set(provider, apps.toPersistentList())
    }
    private suspend fun save(capture: EditorCompleteCapture? = null): EditorDraftCandidate {
        val baseline = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        val owner = EditorDraftOwner(baseline.session.profileId, app.packageName, app.activityName,
            EditorDraftFingerprints.profile(baseline.profile),
            EditorDraftFingerprints.icon(baseline.session.profileId, app.packageName, app.activityName, baseline.row))
        return store.save(store.checkpoint(), owner, EditorCompleteCodec.encode(capture ?: EditorRecoveryFixtures.capture()))
    }
    private suspend fun prepare(checkpoint: EditorDraftCheckpoint) = vm.prepareDraftRecovery(checkpoint, provider, store, EditorRecoveryResources(context))
    private fun open() = vm.getOrCreate("interloper", provider.sessionToken, app, "", "", true)

    @Test fun emptyAndStaleCheckpointsHaveDistinctNonErrorResults() = runBlocking<Unit> {
        val empty = store.checkpoint()
        assertEquals(EditorRecoveryPreparation.Empty, prepare(empty))
        val saved = save()
        assertEquals(EditorRecoveryPreparation.Superseded, prepare(empty))
        assertEquals(saved, store.candidate())
    }

    @Test fun readyUsesAFreshProviderLeaseWithoutAttachingOrChangingStoredBytes() = runBlocking<Unit> {
        val saved = save(); val result = prepare(saved.checkpoint) as EditorRecoveryPreparation.Ready
        try {
            assertEquals(provider.sessionToken, result.draft.baseline.session)
            assertEquals(saved.record.owner, result.draft.owner)
            assertEquals(saved, store.candidate())
            assertNull(vm.getOrCreate("unused", provider.sessionToken, app, "", "", false))
            assertNotEquals("previous-process", result.draft.baseline.session.providerInstanceId)
        } finally { result.draft.bound.disposeUnattached() }
    }

    @Test fun changedPersistedIconIsObsoleteWithoutDeletingTheCheckpoint() = runBlocking<Unit> {
        val saved = save()
        repository.replaceAll(provider.sessionToken.profileId, listOf(DbApplication(app.packageName, app.activityName, false, false, "changed")))
        assertEquals(EditorRecoveryPreparation.Obsolete, prepare(saved.checkpoint)); assertEquals(saved, store.candidate())
    }

    @Test fun changedProfileStyleIsObsoleteWithoutDecodingArtwork() = runBlocking<Unit> {
        val saved = save(); val profile = requireNotNull(provider.activeProfile())
        val changed = dev.renkinProject.renkin.data.ProfileStyleCodec.decode(profile.profileStyle)
        repository.writeProfileStyles(listOf(dev.renkinProject.renkin.data.ProfileStyleUpdate(profile.id,
            dev.renkinProject.renkin.data.ProfileStyleCodec.encode(changed.copy(source = changed.source.copy(fontPath = "different"))),
            profile.prefsSnapshot)))
        assertEquals(EditorRecoveryPreparation.Obsolete, prepare(saved.checkpoint)); assertEquals(saved, store.candidate())
    }

    @Test fun unavailableComponentIsObsoleteAndDifferentProfileNeedsExplicitSwitch() = runBlocking<Unit> {
        val saved = save(); stage(emptyList())
        assertEquals(EditorRecoveryPreparation.Obsolete, prepare(saved.checkpoint))
        val other = provider.createProfile("Other", "", "Other"); provider.switchProfile(other)
        assertEquals(EditorRecoveryPreparation.NeedsProfile(saved.record.owner.profileId), prepare(saved.checkpoint))
        assertEquals(other, provider.runtimeState.value.activeProfileId); assertEquals(saved, store.candidate())
    }

    @Test fun existingLiveEditorSupersedesRecoveryWithoutChangingItsControls() = runBlocking<Unit> {
        val saved = save(); val editor = requireNotNull(open()); editor.customText = "live"
        assertEquals(EditorRecoveryPreparation.Superseded, prepare(saved.checkpoint)); assertEquals("live", editor.customText)
    }

    @Test fun openingAndClosingAnEditorDuringNativeBindingExpiresPreparation() = runBlocking<Unit> {
        val saved = save(); beforeFont = { open(); vm.release("interloper") }
        assertEquals(EditorRecoveryPreparation.Superseded, prepare(saved.checkpoint)); assertEquals(saved, store.candidate())
    }

    @Test fun profileSwitchDuringNativeBindingExpiresPreparation() = runBlocking<Unit> {
        val saved = save(); val other = provider.createProfile("Other", "", "Other")
        beforeFont = { runBlocking { provider.switchProfile(other) } }
        assertEquals(EditorRecoveryPreparation.Superseded, prepare(saved.checkpoint)); assertEquals(saved, store.candidate())
    }

    @Test fun storageReplacementDuringNativeBindingExpiresPreparation() = runBlocking<Unit> {
        val saved = save(); beforeFont = { runBlocking { StorageOperationGate.run { StorageOperationGate.markStorageReplaced() } } }
        assertEquals(EditorRecoveryPreparation.Superseded, prepare(saved.checkpoint)); assertEquals(saved, store.candidate())
    }

    @Test fun newerCheckpointDuringNativeBindingCannotBeReturnedOrDiscardedByOldPreparation() = runBlocking<Unit> {
        val saved = save(); beforeFont = { runBlocking { store.discard(saved.checkpoint) } }
        assertEquals(EditorRecoveryPreparation.Superseded, prepare(saved.checkpoint)); assertNull(store.candidate())
    }

    @Test fun resourceIoFailurePreservesTheLastCheckpoint() = runBlocking<Unit> {
        val saved = save(); beforeFont = { throw IOException("Unwritable cache") }
        assertTrue(prepare(saved.checkpoint) is EditorRecoveryPreparation.Failed); assertEquals(saved, store.candidate())
    }

    @Test fun allocationFailurePreservesTheLastCheckpoint() = runBlocking<Unit> {
        val saved = save(); beforeFont = { throw OutOfMemoryError("Native preparation") }
        assertTrue(prepare(saved.checkpoint) is EditorRecoveryPreparation.Failed); assertEquals(saved, store.candidate())
    }

    @Test fun cancellationPropagatesAndPreservesTheLastCheckpoint() = runBlocking<Unit> {
        val saved = save(); beforeFont = { throw CancellationException("Cancel preparation") }
        try { prepare(saved.checkpoint); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
        assertEquals(saved, store.candidate())
    }

    @Test fun wireValidButOutOfDomainDraftReturnsFailedAndRemainsRetryable() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture(); val controls = value.snapshot.controls
        val saved = save(value.copy(snapshot = value.snapshot.copy(controls = controls.copy(upload = controls.upload.copy(zoomLevel = 3f)))))
        assertTrue(prepare(saved.checkpoint) is EditorRecoveryPreparation.Failed); assertEquals(saved, store.candidate())
        assertTrue(prepare(saved.checkpoint) is EditorRecoveryPreparation.Failed)
    }

    @Test fun corruptArtifactReturnsFailedWithoutReplacingItsCatalog() = runBlocking<Unit> {
        val saved = save(); val catalog = File(context.noBackupFilesDir, "editor-draft/catalog.json").readBytes()
        File(context.noBackupFilesDir, "editor-draft/${saved.record.id}-controls.blob").writeBytes(byteArrayOf(0))
        assertTrue(prepare(saved.checkpoint) is EditorRecoveryPreparation.Failed)
        assertArrayEquals(catalog, File(context.noBackupFilesDir, "editor-draft/catalog.json").readBytes())
    }
}
