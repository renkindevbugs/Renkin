package dev.renkinProject.renkin

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
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
import dev.renkinProject.renkin.ui.EditorRecoveryAcceptance
import dev.renkinProject.renkin.ui.PreparedEditorRecovery
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.ui.captureRenderWitness
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val draftInvalidation get() = EditorDraftInvalidation(store)
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
        val installedIcons = dev.renkinProject.renkin.packages.InstalledIconLoader(context)
        store = EditorDraftStore(context)
        provider = AppModule.provideApplicationProvider(context, AppModule.provideRenkinPackStore(context, repository), repository,
            icons, locks, profiles, AppModule.provideIconGenerationService(context, icons, catalog, installedIcons),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog),
            editorDraftInvalidation = draftInvalidation, installedIconLoader = installedIcons)
        provider.ensureInitialized()
        repository.writeProfileStyles(listOf(dev.renkinProject.renkin.data.ProfileStyleUpdate(provider.sessionToken.profileId,
            dev.renkinProject.renkin.data.ProfileStyleCodec.encode(dev.renkinProject.renkin.data.ProfileStyle()), "")))
        app = EditorRecoveryFixtures.application()
        stage(listOf(app))
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

    private suspend fun ready(): PreparedEditorRecovery =
        (prepare(save().checkpoint) as EditorRecoveryPreparation.Ready).draft

    private suspend fun accept(prepared: PreparedEditorRecovery) = vm.acceptDraftRecovery("recovered", prepared, provider, store)

    private fun pixels(prepared: PreparedEditorRecovery) =
        prepared.bound.artwork.values.filterIsInstance<BitmapIconDrawable>().map { it.drawable.bitmap }

    private suspend fun withPublicationMain(
        beforePublication: (CoroutineContext) -> Unit = {},
        action: suspend () -> Unit
    ) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "editor-recovery-main") }.asCoroutineDispatcher()
        val dispatches = AtomicInteger()
        val dispatchFailure = AtomicReference<Throwable?>()
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = executor.dispatch(context, Runnable {
                // Ownership check, reconstruction, then gated publication.
                try {
                    if (dispatches.incrementAndGet() == 3) beforePublication(context)
                } catch (error: Throwable) { dispatchFailure.set(error) }
                block.run()
            })
        })
        try { action(); dispatchFailure.get()?.let { throw it } }
        finally { Dispatchers.setMain(UnconfinedTestDispatcher()); executor.close() }
    }

    @Test fun acceptedSessionPublishesExactOwnerOnceAndDisposalCannotRecycleLiveArtwork() = runBlocking<Unit> {
        val prepared = ready(); val checkpoint = store.checkpoint(); val bitmaps = pixels(prepared)
        val cached = prepared.sessionForAcceptance()
        try {
            val result = accept(prepared) as EditorRecoveryAcceptance.Accepted
            assertEquals(result.attachment, vm.recoveryAttachment.value)
            assertEquals(provider.sessionToken, result.attachment.owner)
            assertSame(cached, vm.getOrCreate("recovered", provider.sessionToken, app, "", "", false))
            assertNotSame(app, cached.application)
            assertSame(app, cached.baselineApplication)
            prepared.disposeUnattached()
            assertTrue(bitmaps.isNotEmpty()); assertTrue(bitmaps.none { it.isRecycled })
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            vm.release("older")
            assertEquals(result.attachment, vm.recoveryAttachment.value)
            vm.release("recovered")
            assertNull(vm.recoveryAttachment.value)
            assertTrue(bitmaps.none { it.isRecycled })
            assertEquals(checkpoint, store.checkpoint())
        } finally { prepared.disposeUnattached() }
    }

    @Test fun anotherVmCannotAcceptPreparationEvenAtTheSameGeneration() = runBlocking<Unit> {
        val prepared = ready()
        try {
            assertEquals(EditorRecoveryAcceptance.Superseded,
                EditorSessionViewModel().acceptDraftRecovery("foreign", prepared, provider, store))
            assertTrue(prepared.canTransfer)
            assertTrue(accept(prepared) is EditorRecoveryAcceptance.Accepted)
        } finally { vm.release("recovered"); prepared.disposeUnattached() }
    }

    @Test fun openAndReleaseExpiresAcceptanceWithoutReplacingTheCheckpoint() = runBlocking<Unit> {
        val prepared = ready(); val checkpoint = store.checkpoint()
        try {
            open(); vm.release("interloper")
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            assertTrue(prepared.canTransfer); assertNull(vm.recoveryAttachment.value)
            assertEquals(checkpoint, store.checkpoint())
        } finally { prepared.disposeUnattached() }
    }

    @Test fun clearedVmAndDisposedPreparationCannotBeAccepted() = runBlocking<Unit> {
        val prepared = ready(); val bitmaps = pixels(prepared)
        ViewModelStore().apply { put("editor", vm) }.clear()
        assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
        prepared.disposeUnattached(); prepared.disposeUnattached()
        assertTrue(bitmaps.all { it.isRecycled })
        assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
    }

    @Test fun profileSwitchAfterPreparationRejectsAcceptanceWithoutSwitchingBack() = runBlocking<Unit> {
        val prepared = ready(); val checkpoint = store.checkpoint()
        try {
            val other = provider.createProfile("Other", "", "Other"); provider.switchProfile(other)
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            assertEquals(other, provider.runtimeState.value.activeProfileId)
            assertEquals(checkpoint, store.checkpoint()); assertTrue(prepared.canTransfer)
        } finally { prepared.disposeUnattached() }
    }

    @Test fun changedIconRowAfterPreparationRejectsAcceptance() = runBlocking<Unit> {
        val prepared = ready()
        try {
            repository.replaceAll(provider.sessionToken.profileId, listOf(DbApplication(app.packageName, app.activityName, false, false, "changed")))
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared)); assertTrue(prepared.canTransfer)
        } finally { prepared.disposeUnattached() }
    }

    @Test fun changedProfileStyleAfterPreparationRejectsAcceptance() = runBlocking<Unit> {
        val prepared = ready()
        try {
            val profile = requireNotNull(provider.activeProfile())
            val style = dev.renkinProject.renkin.data.ProfileStyleCodec.decode(profile.profileStyle)
            repository.writeProfileStyles(listOf(dev.renkinProject.renkin.data.ProfileStyleUpdate(profile.id,
                dev.renkinProject.renkin.data.ProfileStyleCodec.encode(style.copy(source = style.source.copy(fontPath = "changed"))), profile.prefsSnapshot)))
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
        } finally { prepared.disposeUnattached() }
    }

    @Test fun replacedLiveIdentityAndStorageEpochExpireAcceptance() = runBlocking<Unit> {
        val prepared = ready()
        try {
            stage(listOf(EditorRecoveryFixtures.application()))
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            stage(listOf(app))
            StorageOperationGate.run { StorageOperationGate.markStorageReplaced() }
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
        } finally { prepared.disposeUnattached() }
    }

    @Test fun savedRevisionExpiresAcceptanceEvenWhenTheSavedRowIsUnchanged() = runBlocking<Unit> {
        provider.saveActiveProfileIcons(provider.sessionToken)
        val prepared = ready()
        try {
            provider.saveActiveProfileIcons(provider.sessionToken)
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
        } finally { prepared.disposeUnattached() }
    }

    @Test fun storeSaveDuringGatedMainHandoffSupersedesAcceptance() = runBlocking<Unit> {
        val prepared = ready()
        try {
            withPublicationMain(beforePublication = {
                runBlocking { store.save(prepared.checkpoint, prepared.owner, EditorCompleteCodec.encode(prepared.bound.capture)) }
            }) { assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared)) }
            assertTrue(prepared.canTransfer); assertNull(vm.recoveryAttachment.value)
            assertNotEquals(prepared.checkpoint, store.checkpoint())
        } finally { prepared.disposeUnattached() }
    }

    @Test fun storeDiscardDuringGatedMainHandoffSupersedesAcceptance() = runBlocking<Unit> {
        val prepared = ready()
        try {
            withPublicationMain(beforePublication = { runBlocking { store.discard(prepared.checkpoint) } }) {
                assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            }
            assertTrue(prepared.canTransfer); assertNull(store.candidate())
        } finally { prepared.disposeUnattached() }
    }

    @Test fun invalidatedStorageDuringMainHandoffExpiresAcceptanceWithoutAttaching() = runBlocking<Unit> {
        val prepared = ready()
        try {
            withPublicationMain(beforePublication = { runBlocking { store.invalidateStorage() } }) {
                assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            }
            assertTrue(prepared.canTransfer); assertNull(vm.recoveryAttachment.value)
            assertNull(store.candidate())
        } finally { prepared.disposeUnattached() }
    }

    @Test fun disposalDuringMainHandoffPreventsTransferOfRecycledArtwork() = runBlocking<Unit> {
        val prepared = ready(); val bitmaps = pixels(prepared)
        withPublicationMain(beforePublication = { prepared.disposeUnattached() }) {
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
        }
        assertNull(vm.recoveryAttachment.value); assertTrue(bitmaps.all { it.isRecycled })
    }

    @Test fun editorOpenedDuringMainHandoffIsNeverReplacedByRecovery() = runBlocking<Unit> {
        val prepared = ready()
        try {
            withPublicationMain(beforePublication = { open()?.customText = "live" }) {
                assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            }
            assertEquals("live", open()?.customText); assertTrue(prepared.canTransfer)
        } finally { vm.release("interloper"); prepared.disposeUnattached() }
    }

    @Test fun cancellationBeforePublicationPreservesUnattachedOwnershipAndCheckpoint() = runBlocking<Unit> {
        val prepared = ready(); val checkpoint = store.checkpoint()
        try {
            withPublicationMain(beforePublication = { it[Job]?.cancel() }) {
                val accepting = async { accept(prepared) }
                try { accepting.await(); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
            }
            assertTrue(prepared.canTransfer); assertNull(vm.recoveryAttachment.value)
            assertEquals(checkpoint, store.checkpoint())
        } finally { prepared.disposeUnattached() }
    }

    @Test fun cancellationAfterPublicationRemainsObservableAndCannotRecycleAttachedPixels() = runBlocking<Unit> {
        val prepared = ready(); val bitmaps = pixels(prepared)
        try {
            withPublicationMain {
                val accepting = async(start = CoroutineStart.LAZY) { accept(prepared) }
                val observing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    vm.recoveryAttachment.filterNotNull().first()
                    accepting.cancel()
                }
                accepting.start()
                try { accepting.await(); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
                observing.join()
            }
            assertNotNull(vm.recoveryAttachment.value)
            assertNotNull(vm.getOrCreate("recovered", provider.sessionToken, app, "", "", false))
            prepared.disposeUnattached(); assertTrue(bitmaps.none { it.isRecycled })
        } finally { vm.release("recovered"); prepared.disposeUnattached() }
    }

    @Test fun gateTriggeredRecoveryRunsOffMainAndPublicationRunsOnMain() = runBlocking<Unit> {
        val prepared = ready(); var recoveryThread: String? = null; var publicationThread: String? = null
        try {
            StorageOperationGate.installRecovery { recoveryThread = Thread.currentThread().name }
            withPublicationMain(beforePublication = { publicationThread = Thread.currentThread().name }) {
                assertTrue(accept(prepared) is EditorRecoveryAcceptance.Accepted)
            }
            assertNotNull(recoveryThread); assertNotEquals("editor-recovery-main", recoveryThread)
            assertEquals("editor-recovery-main", publicationThread)
        } finally { vm.release("recovered"); prepared.disposeUnattached() }
    }

    @Test fun unavailableStorageSupersedesPreparationAndAcceptanceWithoutDiscardingDraft() = runBlocking<Unit> {
        val prepared = ready(); val checkpoint = store.checkpoint()
        try {
            StorageOperationGate.requireRecovery()
            assertEquals(EditorRecoveryPreparation.Superseded, prepare(checkpoint))
            assertEquals(EditorRecoveryAcceptance.Superseded, accept(prepared))
            assertEquals(checkpoint, store.checkpoint()); assertTrue(prepared.canTransfer)
        } finally { prepared.disposeUnattached() }
    }

    @Test fun corruptCheckpointDuringAcceptanceFailsWithoutDisposingPreviewResources() = runBlocking<Unit> {
        val prepared = ready(); val bitmaps = pixels(prepared)
        try {
            File(context.noBackupFilesDir, "editor-draft/catalog.json").writeText("{broken")
            assertTrue(accept(prepared) is EditorRecoveryAcceptance.Failed)
            assertTrue(prepared.canTransfer); assertTrue(bitmaps.none { it.isRecycled })
            assertNull(vm.recoveryAttachment.value)
        } finally { prepared.disposeUnattached() }
    }

    @Test fun replacingAcceptedEditorClearsAttachmentWithoutRecyclingClosingPreview() = runBlocking<Unit> {
        val prepared = ready(); val bitmaps = pixels(prepared)
        try {
            assertTrue(accept(prepared) is EditorRecoveryAcceptance.Accepted)
            open()
            assertNull(vm.recoveryAttachment.value)
            prepared.disposeUnattached(); assertTrue(bitmaps.none { it.isRecycled })
            ViewModelStore().apply { put("editor", vm) }.clear()
            assertTrue(bitmaps.none { it.isRecycled })
        } finally { prepared.disposeUnattached() }
    }

    @Test fun replacedLiveApplicationSupersedesCaptureWithoutARecoveryError() = runBlocking<Unit> {
        val editor = requireNotNull(open())
        editor.draft.regenerateCreate(EditorRecoveryFixtures.builder, app, EditorRecoveryFixtures.options(), emptyList(),
            editor.captureRenderWitness())
        stage(listOf(EditorRecoveryFixtures.application()))
        assertEquals(EditorCaptureResult.Superseded, vm.captureCompleteDraft("interloper", store.checkpoint(), provider, store,
            EditorDraftResources(context), dev.renkinProject.renkin.data.IconModifierInputStore(context)))
    }

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
