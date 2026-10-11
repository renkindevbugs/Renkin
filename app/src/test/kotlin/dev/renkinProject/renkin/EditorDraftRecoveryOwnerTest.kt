package dev.renkinProject.renkin

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
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
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
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
        main.close()
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
}
