package dev.renkinProject.renkin

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.room.Room
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.ProfileStyle
import dev.renkinProject.renkin.data.ProfileStyleCodec
import dev.renkinProject.renkin.data.ProfileStyleUpdate
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.editor.EditorCompleteCodec
import dev.renkinProject.renkin.data.editor.EditorDraftCandidate
import dev.renkinProject.renkin.data.editor.EditorDraftFingerprints
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.editor.EditorDraftOwner
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import dev.renkinProject.renkin.di.AppModule
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.EditorRecoveryFixtures
import dev.renkinProject.renkin.ui.PreparedEditorRecovery
import dev.renkinProject.renkin.ui.EditorSession
import dev.renkinProject.renkin.ui.captureRenderWitness
import dev.renkinProject.renkin.ui.renderWitnessOrNull
import dev.renkinProject.renkin.ui.withModifierAdjustments
import dev.renkinProject.renkin.ui.BrushStroke
import dev.renkinProject.renkin.ui.buildEraseMask
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.editor.EditorControlsCodec
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
class EditorDraftRecoveryOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val mainThread = AtomicReference<Thread>()
    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "editor-owner-main").also { mainThread.set(it) }
    }.asCoroutineDispatcher()
    // Production viewModelScope uses Main.immediate: channel sends may resume its receiver inline.
    private val main = object : MainCoroutineDispatcher() {
        override val immediate get() = this
        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== mainThread.get()
        override fun dispatch(context: CoroutineContext, block: Runnable) = mainExecutor.dispatch(context, block)
    }
    private val models = ViewModelStore()
    private lateinit var database: RenkinPackDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var provider: ApplicationProvider
    private lateinit var context: Context
    private lateinit var store: EditorDraftStore
    private lateinit var app: PackageInfoStruct
    private lateinit var owner: EditorDraftRecoveryViewModel
    private val editor = EditorSessionViewModel()

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(main)
        StorageOperationGate.resetForTests()
        val privateDir = temporary.newFolder("private")
        val cache = temporary.newFolder("cache")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = privateDir
            override fun getCacheDir() = cache
        }
        context.dataStore.edit { it.clear() }
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        repository = RenkinPackRepository(database)
        val catalog = AppModule.provideIconPackCatalog(context)
        val icons = AppModule.provideIconPackRepository(context, catalog)
        val locks = AppModule.provideIconLockManager(context, repository)
        val profiles = AppModule.provideProfileManager(context, repository)
        store = EditorDraftStore(context)
        provider = AppModule.provideApplicationProvider(context, AppModule.provideRenkinPackStore(context, repository), repository,
            icons, locks, profiles, AppModule.provideIconGenerationService(context, icons, catalog),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog),
            editorDraftInvalidation = EditorDraftInvalidation(store))
        provider.ensureInitialized()
        repository.writeProfileStyles(listOf(ProfileStyleUpdate(provider.sessionToken.profileId,
            ProfileStyleCodec.encode(ProfileStyle()), "")))
        app = EditorRecoveryFixtures.application()
        stage(app)
        owner = newOwner()
        models.put("editor", editor)
    }

    @After fun cleanup() = runBlocking {
        BackupOsShadow.beforeDirectorySync = null
        withContext(main) { models.clear() }
        database.close()
        Dispatchers.resetMain()
        mainExecutor.close()
        StorageOperationGate.resetForTests()
    }

    private fun newOwner() = EditorDraftRecoveryViewModel(context, provider, store).also {
        models.put("owner", it)
    }
    private fun stage(application: PackageInfoStruct) {
        ApplicationProvider::class.java.getDeclaredField("stagedApplications").apply { isAccessible = true }
            .set(provider, listOf(application).toPersistentList())
    }
    private suspend fun save(): EditorDraftCandidate {
        val baseline = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        val identity = EditorDraftOwner(baseline.session.profileId, app.packageName, app.activityName,
            EditorDraftFingerprints.profile(baseline.profile),
            EditorDraftFingerprints.icon(baseline.session.profileId, app.packageName, app.activityName, baseline.row))
        return store.save(store.checkpoint(), identity, EditorCompleteCodec.encode(EditorRecoveryFixtures.capture()))
    }
    private suspend fun connect() = withContext(main) { owner.connect(editor) }
    private suspend inline fun <reified T : EditorDraftRecoveryState> shown(): T =
        withTimeout(30_000) { owner.state.first { it is T } as T }
    private suspend fun offer(): EditorDraftRecoveryState.Offer { save(); connect(); return shown() }
    private suspend fun prepared(): PreparedEditorRecovery = withContext(main) {
        EditorDraftRecoveryViewModel::class.java.getDeclaredField("prepared").apply { isAccessible = true }
            .get(owner) as PreparedEditorRecovery
    }
    private suspend fun restore() = offer().let {
        withContext(main) { owner.restore(it) }
        withTimeout(30_000) { editor.recoveryAttachment.first { it != null }!! }
    }
    private suspend fun idle() { delay(150); withContext(main) { } }

    @Test fun restoreTransfersThemedSessionAndExplicitCloseDiscardsOnlyItsCheckpoint() = runBlocking {
        val attachment = restore()
        val checkpoint = store.checkpoint()
        assertEquals(provider.sessionToken, attachment.owner)
        assertEquals(false, attachment.themed)
        withContext(main) {
            assertNotNull(editor.recoveredSession(attachment))
            owner.close(attachment)
        }
        withTimeout(30_000) { while (store.checkpoint() == checkpoint) delay(10) }
        assertNull(store.candidate())
        assertNull(editor.recoveryAttachment.value)
    }

    @Test fun notNowDropsUnattachedPixelsAndPreservesFilesAcrossReconnect() = runBlocking {
        val offered = offer()
        val bound = prepared()
        val pixels = bound.bound.artwork.values.filterIsInstance<BitmapIconDrawable>().map { it.drawable.bitmap }
        withContext(main) { owner.postpone(offered) }
        shown<EditorDraftRecoveryState.Hidden>()
        assertTrue(pixels.isNotEmpty())
        assertTrue(pixels.all { it.isRecycled })
        assertEquals(offered.checkpoint, store.checkpoint())
        connect(); idle()
        assertEquals(EditorDraftRecoveryState.Hidden, owner.state.value)
        assertFalse(bound.canTransfer)
    }

    @Test fun freshOwnerOffersMemoryOnlyPostponedDraftAgain() = runBlocking {
        val offered = offer()
        withContext(main) { owner.postpone(offered) }
        shown<EditorDraftRecoveryState.Hidden>()
        withContext(main) { owner = newOwner() }
        connect()
        assertEquals(offered.checkpoint, shown<EditorDraftRecoveryState.Offer>().checkpoint)
    }

    @Test fun sameEditorReconnectDoesNotDecodeOrRecycleOffer() = runBlocking {
        val offered = offer(); val original = prepared()
        connect(); idle()
        assertSame(offered, owner.state.value)
        assertSame(original, prepared())
        assertTrue(original.canTransfer)
    }

    @Test fun bulkApplicationPublicationsDoNotReprepareTheOffer() = runBlocking {
        val offered = offer(); val original = prepared()
        val revision = provider.applicationState.value.revision
        repeat(50) {
            stage(EditorRecoveryFixtures.application())
            ApplicationProvider::class.java.getDeclaredMethod("publishApplicationState").apply { isAccessible = true }.invoke(provider)
        }
        idle()
        assertTrue(provider.applicationState.value.revision > revision)
        assertSame(offered, owner.state.value)
        assertSame(original, prepared())
        assertTrue(original.canTransfer)
    }

    @Test fun unexpectedSessionLossPreservesCheckpointAndOffersItAgain() = runBlocking {
        val attachment = restore(); val checkpoint = store.checkpoint()
        withContext(main) { owner.detach(attachment) }
        assertEquals(checkpoint, shown<EditorDraftRecoveryState.Offer>().checkpoint)
        assertEquals(checkpoint, store.checkpoint())
        assertNull(editor.recoveryAttachment.value)
    }

    @Test fun staleUiActionCannotDiscardAnotherShownAttempt() = runBlocking {
        val old = offer()
        withContext(main) { owner.retry(old) }
        val newer = withTimeout(30_000) {
            owner.state.first { it is EditorDraftRecoveryState.Offer && it !== old }
        }
        withContext(main) { owner.discard(old); owner.postpone(old); owner.restore(old) }
        idle()
        assertSame(newer, owner.state.value)
        assertEquals(old.checkpoint, store.checkpoint())
        assertNull(editor.recoveryAttachment.value)
    }

    @Test fun staleCheckpointDiscardRefreshesAndPreservesNewDraft() = runBlocking {
        val old = offer(); val newer = save()
        withContext(main) { owner.discard(old) }
        assertEquals(newer.checkpoint, withTimeout(30_000) {
            owner.state.first { it is EditorDraftRecoveryState.Offer && it.checkpoint == newer.checkpoint }
        }.let { (it as EditorDraftRecoveryState.Offer).checkpoint })
        assertEquals(newer.checkpoint, store.checkpoint())
    }

    @Test fun supersededRestoreAutomaticallyPreparesTheNewCheckpoint() = runBlocking {
        val old = offer(); val newer = save()
        withContext(main) { owner.restore(old) }
        withTimeout(30_000) {
            owner.state.first { it is EditorDraftRecoveryState.Offer && it.checkpoint == newer.checkpoint }
        }
        assertTrue(prepared().canTransfer)
        assertNull(editor.recoveryAttachment.value)
    }

    @Test fun changedSavedIconIsInformationalObsolete() = runBlocking {
        save()
        repository.replaceAll(provider.sessionToken.profileId,
            listOf(DbApplication(app.packageName, app.activityName, false, false, "changed")))
        connect()
        assertEquals(store.checkpoint(), shown<EditorDraftRecoveryState.Obsolete>().checkpoint)
    }

    @Test fun anotherProfileIsNamedWithoutSwitchingIt() = runBlocking {
        val candidate = save()
        val other = provider.createProfile("Other", "", "Other")
        provider.switchProfile(other)
        connect()
        val shown = shown<EditorDraftRecoveryState.NeedsProfile>()
        assertEquals(candidate.record.owner.profileId, shown.profileId)
        assertTrue(shown.profileName.isNotBlank())
        assertEquals(other, provider.runtimeState.value.activeProfileId)
    }

    @Test fun retryDoesNotResetUnreadableMetadataAndConfirmedDiscardDoes() = runBlocking {
        store.checkpoint()
        val catalog = File(context.noBackupFilesDir, "editor-draft/catalog.json")
        catalog.writeText("broken")
        connect()
        val failed = shown<EditorDraftRecoveryState.Failed>()
        assertNotNull(failed.resetLease)
        withContext(main) { owner.retry(failed) }
        val retried = withTimeout(30_000) { owner.state.first { it is EditorDraftRecoveryState.Failed && it !== failed } }
        assertEquals("broken", catalog.readText())
        withContext(main) { owner.discard(retried) }
        shown<EditorDraftRecoveryState.Hidden>()
        assertNull(store.candidate())
    }

    @Test fun unreadableErrorCannotDiscardARepairedCatalog() = runBlocking {
        store.checkpoint()
        val catalog = File(context.noBackupFilesDir, "editor-draft/catalog.json")
        val valid = catalog.readBytes()
        catalog.writeText("broken")
        connect()
        val failed = shown<EditorDraftRecoveryState.Failed>()
        catalog.writeBytes(valid)
        val newer = save()
        withContext(main) { owner.discard(failed) }
        assertEquals(newer.checkpoint, shown<EditorDraftRecoveryState.Offer>().checkpoint)
        assertEquals(newer.checkpoint, store.checkpoint())
    }

    @Test fun failedExplicitCleanupIsVisibleAndRetryClearsIt() = runBlocking {
        val attachment = restore(); val checkpoint = store.checkpoint()
        BackupOsShadow.beforeDirectorySync = { path ->
            if (File(path).name == "editor-draft") throw IOException("Injected directory fsync failure")
        }
        withContext(main) { owner.close(attachment) }
        val failed = shown<EditorDraftRecoveryState.Failed>()
        assertTrue(failed.closingEditor)
        assertNull(editor.recoveryAttachment.value)
        BackupOsShadow.beforeDirectorySync = null
        withContext(main) { owner.retry(failed) }
        shown<EditorDraftRecoveryState.Hidden>()
        assertNotEquals(checkpoint, store.checkpoint())
        assertNull(store.candidate())
    }

    private suspend fun openLive(): EditorSession {
        connect()
        return withContext(main) {
            requireNotNull(editor.getOrCreate("live", provider.sessionToken, app, "pack", "", allowCreate = true)).also {
                publish(it)
            }
        }
    }

    private suspend fun publish(session: EditorSession, change: EditorSession.() -> Unit = {}) = withContext(main) {
        session.change()
        val mask = session.adjustments.eraseStrokes.takeIf { it.isNotEmpty() }?.let { buildEraseMask(it) }
        val options = EditorRecoveryFixtures.options().withModifierAdjustments(session.adjustments, session.imageEdit, mask)
        session.draft.regenerateCreate(EditorRecoveryFixtures.builder, session.application, options,
            session.customIconList, session.renderWitnessOrNull())
        session.draft.acknowledgeWitness(session.renderWitnessOrNull(), session.application, options, session.customIconList)
        Snapshot.sendApplyNotifications()
    }

    private suspend fun saved(after: EditorDraftCheckpoint? = null): EditorDraftCandidate = withTimeout(30_000) {
        while (true) {
            val candidate = store.candidate()
            if (candidate != null && candidate.checkpoint != after &&
                owner.autosaveState.value?.status == EditorAutosaveStatus.SAVED &&
                owner.autosaveState.value?.checkpoint == candidate.checkpoint) return@withTimeout candidate
            delay(20)
        }
        error("Unreachable")
    }

    private suspend fun savedScale(candidate: EditorDraftCandidate) =
        EditorControlsCodec.decode(store.artifact(candidate.checkpoint, "controls")).adjustments.iconScale

    private suspend fun quiet() { delay(850); withContext(main) { } }

    @Test fun openingAndNavigationDoNotCreateDraftAndFirstRealEditStillSavesInline() = runBlocking {
        val session = openLive()
        quiet(); assertNull(store.candidate())
        publish(session) { selectedTab = 2; browsingIconPacks = true }
        quiet(); assertNull(store.candidate())
        publish(session) { adjustments.iconScale = 0.7f }
        assertEquals(0.7f, savedScale(saved()), 0f)
    }

    @Test fun successiveChangesAfterUnchangedSaveRequestsPersistLatestControls() = runBlocking {
        val session = openLive()
        quiet()
        publish(session) { selectedTab = 2; adjustments.iconScale = 0.7f }
        val first = saved()
        publish(session) { selectedTab = 0 }
        quiet(); assertEquals(first.checkpoint, store.checkpoint())
        publish(session) { selectedTab = 2; adjustments.iconScale = 0.5f }
        assertEquals(0.5f, savedScale(saved(first.checkpoint)), 0f)
    }

    @Test fun routineChangesDebounceAndCoalesceToLatestFrame() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.8f }
        delay(100)
        publish(session) { adjustments.iconScale = 0.6f }
        delay(100)
        assertNull(store.candidate())
        assertEquals(0.6f, savedScale(saved()), 0f)
    }

    @Test fun returningToOpeningControlsReplacesAlreadySavedEdit() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val first = saved()
        publish(session) { adjustments.iconScale = 1f }
        assertEquals(1f, savedScale(saved(first.checkpoint)), 0f)
    }

    @Test fun backgroundFlushBypassesDebounceAndForegroundRestoresIt() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.8f }
        withContext(main) { owner.setBackgrounded(true) }
        val first = saved()
        withContext(main) { owner.setBackgrounded(false) }
        publish(session) { adjustments.iconScale = 0.6f }
        delay(150); assertEquals(first.checkpoint, store.checkpoint())
        assertEquals(0.6f, savedScale(saved(first.checkpoint)), 0f)
    }

    @Test fun repeatedSessionObservationDoesNotPreventLaterRecoveryOffer() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val first = saved()
        connect(); connect()
        withContext(main) { editor.release("live") }
        assertEquals(first.checkpoint, shown<EditorDraftRecoveryState.Offer>().checkpoint)
    }

    @Test fun explicitCloseCancelsDebounceAndDoesNotLeaveDraft() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        withContext(main) { owner.closeLive("live") }
        quiet()
        assertNull(store.candidate()); assertNull(editor.lifetime.value.sessionId)
    }

    @Test fun explicitCloseClearsLatestOfSeveralAutosaves() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val first = saved()
        publish(session) { adjustments.iconScale = 0.5f }
        val last = saved(first.checkpoint)
        withContext(main) { owner.closeLive("live") }
        withTimeout(30_000) { while (store.checkpoint() == last.checkpoint) delay(20) }
        quiet(); assertNull(store.candidate())
    }

    @Test fun postponedForeignDraftBlocksAutosaveAndClosePreservesIt() = runBlocking {
        val offered = offer()
        withContext(main) { owner.postpone(offered) }
        shown<EditorDraftRecoveryState.Hidden>()
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        withTimeout(30_000) { owner.autosaveState.first { it?.status == EditorAutosaveStatus.BLOCKED } }
        quiet()
        assertEquals(offered.checkpoint, store.checkpoint())
        assertEquals(app.originalName, owner.autosaveState.value?.draftApplicationName)
        withContext(main) { owner.closeLive("live") }
        quiet(); assertEquals(offered.checkpoint, store.checkpoint())
    }

    @Test fun confirmedForeignDraftDiscardAdmitsCurrentEdit() = runBlocking {
        val offered = offer()
        withContext(main) { owner.postpone(offered) }
        shown<EditorDraftRecoveryState.Hidden>()
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val blocked = withTimeout(30_000) { owner.autosaveState.first { it?.status == EditorAutosaveStatus.BLOCKED }!! }
        withContext(main) { owner.discardForAutosave(blocked) }
        assertEquals(0.7f, savedScale(saved(offered.checkpoint)), 0f)
    }

    @Test fun unsupportedWitnessFailsWithoutReplacingCheckpointAndSupportedEditResumes() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val first = saved()
        publish(session) { customText = "x".repeat(EditorControlsCodec.MAX_TEXT_BYTES + 1) }
        val failed = withTimeout(30_000) { owner.autosaveState.first { it?.status == EditorAutosaveStatus.FAILED }!! }
        assertEquals(first.checkpoint, store.checkpoint())
        publish(session) { adjustments.iconScale = 0.6f }
        quiet(); assertSame(failed, owner.autosaveState.value)
        publish(session) { customText = "" }
        assertEquals(0.6f, savedScale(saved(first.checkpoint)), 0f)
    }

    @Test fun captureFailurePreservesCheckpointAndExplicitRetryRecovers() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val first = saved()
        publish(session) { textFontPath = "/unowned/font.ttf"; adjustments.iconScale = 0.6f }
        val failed = withTimeout(30_000) { owner.autosaveState.first { it?.status == EditorAutosaveStatus.FAILED }!! }
        assertEquals(first.checkpoint, store.checkpoint())
        withContext(main) { session.textFontPath = ""; owner.retryAutosave(failed) }
        // Publish a coherent witness before Retry can capture the corrected resource selection.
        publish(session)
        assertEquals(0.6f, savedScale(saved(first.checkpoint)), 0f)
    }

    @Test fun recoveredUntouchedFrameIsNotRewrittenAndFurtherEditsAreSaved() = runBlocking {
        val attachment = restore()
        val checkpoint = store.checkpoint()
        quiet(); assertEquals(checkpoint, store.checkpoint())
        val session = withContext(main) { requireNotNull(editor.recoveredSession(attachment)) }
        publish(session) { adjustments.iconScale = 0.7f }
        val changed = saved(checkpoint)
        assertEquals(0.7f, savedScale(changed), 0f)
        withContext(main) { owner.close(attachment) }
        withTimeout(30_000) { while (store.checkpoint() == changed.checkpoint) delay(20) }
        assertNull(store.candidate())
    }

    @Test fun autosavedScaleColorizeOutlineAndBrushRestoreTogetherUnderFreshOwner() = runBlocking {
        val session = openLive()
        publish(session) {
            selectedTab = 2
            adjustments.iconScale = 0.7f
            imageEdit = ImageEdit.COLORIZE
            adjustments.outlineMode = OutlineMode.ADD
            adjustments.eraseStrokes = listOf(BrushStroke(0.1f, listOf(Offset(0.2f, 0.3f), Offset(0.4f, 0.5f))))
        }
        val saved = saved()
        withContext(main) { editor.release("live") }
        val offered = shown<EditorDraftRecoveryState.Offer>()
        assertEquals(saved.checkpoint, offered.checkpoint)
        withContext(main) { owner.restore(offered) }
        val attachment = withTimeout(30_000) { editor.recoveryAttachment.first { it != null }!! }
        withContext(main) {
            val restored = requireNotNull(editor.recoveredSession(attachment))
            assertEquals(0.7f, restored.adjustments.iconScale, 0f)
            assertEquals(ImageEdit.COLORIZE, restored.imageEdit)
            assertEquals(OutlineMode.ADD, restored.adjustments.outlineMode)
            assertEquals(session.adjustments.eraseStrokes, restored.adjustments.eraseStrokes)
            assertEquals(2, restored.selectedTab)
            assertEquals(provider.sessionToken, restored.owner)
        }
        quiet(); assertEquals(saved.checkpoint, store.checkpoint())
    }

    @Test fun oldBackgroundWorkCannotResurrectDraftAfterStorageInvalidation() = runBlocking {
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        StorageOperationGate.run {
            store.invalidateStorage()
            StorageOperationGate.markStorageReplaced()
        }
        val checkpoint = store.checkpoint()
        withContext(main) { owner.setBackgrounded(true) }
        quiet(); assertEquals(checkpoint, store.checkpoint()); assertNull(store.candidate())
    }

    @Test fun closeDuringPublicationWaitsForWriteThenDiscardsItsNewCheckpoint() = runBlocking {
        val session = openLive(); quiet()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val syncs = AtomicInteger()
        BackupOsShadow.beforeDirectorySync = { path ->
            if (File(path).name == "editor-draft" && syncs.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
        }
        try {
            publish(session) { adjustments.iconScale = 0.7f }
            assertTrue(withContext(Dispatchers.IO) { entered.await(30, TimeUnit.SECONDS) })
            withContext(main) { owner.closeLive("live") }
            assertNull(editor.lifetime.value.sessionId)
        } finally { release.countDown() }
        withTimeout(30_000) { while (owner.autosaveState.value != null) delay(20) }
        withTimeout(30_000) { while (store.candidate() != null) delay(20) }
        assertNull(store.candidate())
    }

    @Test fun finalSyncFailureReconcilesPublicationOwnershipAndCloseRemovesIt() = runBlocking {
        val session = openLive(); quiet()
        val syncs = AtomicInteger()
        BackupOsShadow.beforeDirectorySync = { path ->
            if (File(path).name == "editor-draft" && syncs.incrementAndGet() == 2) {
                throw IOException("Injected final publication sync failure")
            }
        }
        publish(session) { adjustments.iconScale = 0.7f }
        val failed = withTimeout(30_000) { owner.autosaveState.first { it?.status == EditorAutosaveStatus.FAILED }!! }
        val published = requireNotNull(store.candidate())
        assertEquals(published.checkpoint, failed.checkpoint)
        assertEquals(0.7f, savedScale(published), 0f)
        BackupOsShadow.beforeDirectorySync = null
        withContext(main) { owner.closeLive("live") }
        withTimeout(30_000) { while (store.checkpoint() == published.checkpoint) delay(20) }
        assertNull(store.candidate())
    }

    @Test fun corruptCatalogBlocksCurrentEditorAndConfirmedResetSavesItsChanges() = runBlocking {
        store.checkpoint()
        File(context.noBackupFilesDir, "editor-draft/catalog.json").writeText("broken")
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val failed = withTimeout(30_000) { owner.autosaveState.first { it?.resetLease != null }!! }
        assertEquals(EditorAutosaveStatus.FAILED, failed.status)
        withContext(main) { owner.discardForAutosave(failed) }
        assertEquals(0.7f, savedScale(saved()), 0f)
    }

    @Test fun staleAutosaveDiscardCannotReplaceNewerForeignCheckpoint() = runBlocking {
        val offered = offer()
        withContext(main) { owner.postpone(offered) }
        shown<EditorDraftRecoveryState.Hidden>()
        val session = openLive()
        publish(session) { adjustments.iconScale = 0.7f }
        val blocked = withTimeout(30_000) { owner.autosaveState.first { it?.status == EditorAutosaveStatus.BLOCKED }!! }
        val newer = save()
        withContext(main) { owner.discardForAutosave(blocked) }
        withTimeout(30_000) { owner.autosaveState.first { it?.checkpoint == newer.checkpoint } }
        assertEquals(newer.checkpoint, store.checkpoint())
        assertEquals(EditorAutosaveStatus.BLOCKED, owner.autosaveState.value?.status)
    }
}
