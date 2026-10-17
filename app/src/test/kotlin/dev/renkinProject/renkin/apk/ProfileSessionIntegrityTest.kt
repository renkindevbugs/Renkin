package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.renkinProject.renkin.GlobalOptionsViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.GlobalApplyExistingKey
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.collections.immutable.toPersistentList
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.di.AppModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class ProfileSessionIntegrityTest {
    private val draftInvalidation by lazy { EditorDraftInvalidation(EditorDraftStore(context), UnsavedIconJournalStore(context)) }
    private class DiscoveryContext(base: Context) : ContextWrapper(base) {
        var failDiscovery = false
        override fun getSystemService(name: String): Any? {
            if (failDiscovery && name == USER_SERVICE) throw IllegalStateException("Discovery failed")
            return super.getSystemService(name)
        }
    }

    private lateinit var context: DiscoveryContext
    private lateinit var database: RenkinPackDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var provider: ApplicationProvider

    @Before
    fun setUp() = runBlocking {
        context = DiscoveryContext(RuntimeEnvironment.getApplication())
        context.dataStore.edit { it.clear() }
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RenkinPackRepository(database)
        provider = newProvider()
    }

    @After
    fun tearDown() { database.close() }

    private fun newProvider(): ApplicationProvider {
        val catalog = AppModule.provideIconPackCatalog(context)
        val icons = AppModule.provideIconPackRepository(context, catalog)
        val locks = AppModule.provideIconLockManager(context, repository)
        val profiles = AppModule.provideProfileManager(context, repository)
        val installedIcons = dev.renkinProject.renkin.packages.InstalledIconLoader(context)
        return AppModule.provideApplicationProvider(
            context,
            AppModule.provideRenkinPackStore(context, repository),
            repository,
            icons,
            locks,
            profiles,
            AppModule.provideIconGenerationService(context, icons, catalog, installedIcons),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog),
            editorDraftInvalidation = draftInvalidation,
            installedIconLoader = installedIcons
        )
    }

    private suspend fun expectFailure(reason: ProfileSessionFailureReason, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected session rejection: $reason")
        } catch (error: ProfileSessionChangedException) {
            assertEquals(reason, error.reason)
        }
    }

    private fun stageEditorApplication(): PackageInfoStruct {
        val app = PackageInfoStruct("Editor", "test.editor", "Main", ColorDrawable(0), 0)
        ApplicationProvider::class.java.getDeclaredField("stagedApplications").apply { isAccessible = true }
            .set(provider, listOf(app).toPersistentList())
        return app
    }

    @Test fun editorBaselineReadsDoNotRepublishAnUnchangedApplicationState() = runBlocking {
        provider.ensureInitialized()
        val app = stageEditorApplication()
        val first = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        val published = provider.applicationState.value
        val second = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        assertEquals(first, second)
        assertTrue(provider.validateEditorDraftBaseline(first))
        assertSame(published, provider.applicationState.value)
    }

    @Test fun recoveryBaselineMissingComponentIsAnExpectedAbsentValue() = runBlocking {
        provider.ensureInitialized()
        assertEquals(null, provider.captureEditorRecoveryBaseline(provider.sessionToken, "removed", "Main"))
        val app = stageEditorApplication()
        val baseline = requireNotNull(provider.captureEditorRecoveryBaseline(provider.sessionToken, app.packageName, app.activityName))
        assertSame(app, baseline.application)
        assertTrue(provider.validateEditorDraftBaseline(baseline))
    }

    @Test fun recoveryBaselineRejectsTheInspectedTokenAfterSwitchingAwayAndBack() = runBlocking {
        provider.ensureInitialized()
        val app = stageEditorApplication(); val inspected = provider.sessionToken
        val other = provider.createProfile("Other", "", "Other")
        provider.switchProfile(other)
        expectFailure(ProfileSessionFailureReason.EXPIRED) {
            provider.captureEditorRecoveryBaseline(inspected, app.packageName, app.activityName)
        }
        provider.switchProfile(inspected.profileId)
        expectFailure(ProfileSessionFailureReason.EXPIRED) {
            provider.captureEditorRecoveryBaseline(inspected, app.packageName, app.activityName)
        }
    }

    @Test fun editorBaselineComparesPersistedRowsByValueAndRejectsChangedArtwork() = runBlocking {
        provider.ensureInitialized()
        val app = stageEditorApplication()
        val row = DbApplication(app.packageName, app.activityName, false, false, "original")
        repository.replaceAll(DEFAULT_PROFILE_ID, listOf(row))
        val baseline = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        repository.replaceAll(DEFAULT_PROFILE_ID, listOf(row.copy()))
        assertTrue(provider.validateEditorDraftBaseline(baseline))
        repository.replaceAll(DEFAULT_PROFILE_ID, listOf(row.copy(drawable = "replacement")))
        assertFalse(provider.validateEditorDraftBaseline(baseline))
    }

    @Test fun editorBaselineRejectsReplacedLiveIdentityEvenWithEqualComponentAndVersion() = runBlocking {
        provider.ensureInitialized()
        val app = stageEditorApplication()
        val baseline = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        assertEquals(app, stageEditorApplication())
        assertFalse(provider.validateEditorDraftBaseline(baseline))
    }

    @Test fun editorBaselineRejectsStorageReplacementAndNewProfileOwner() = runBlocking {
        provider.ensureInitialized()
        val app = stageEditorApplication()
        val beforeStorage = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        StorageOperationGate.run { StorageOperationGate.markStorageReplaced() }
        assertFalse(provider.validateEditorDraftBaseline(beforeStorage))
        val beforeSwitch = provider.captureEditorDraftBaseline(provider.sessionToken, app)
        val other = provider.createProfile("Other editor", "", "Other")
        provider.switchProfile(other)
        assertFalse(provider.validateEditorDraftBaseline(beforeSwitch))
    }

    @Test
    fun runtimeStartupWaitResolvesOnlyAfterInitialization() = runBlocking {
        val initialToken = provider.sessionToken
        assertEquals(DEFAULT_PROFILE_ID, initialToken.profileId)
        assertEquals(0L, initialToken.revision)
        assertTrue(initialToken.providerInstanceId.isNotBlank())
        assertEquals(ApplicationRuntimeState(initialToken), provider.runtimeState.value)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            provider.runtimeState.first { it.startupComplete }
        }
        assertFalse(waiting.isCompleted)

        provider.ensureInitialized()

        assertTrue(withTimeout(5_000) { waiting.await() }.ready)
        assertTrue(provider.runtimeState.value.ready)
        assertEquals(provider.sessionToken, provider.applicationState.value.sessionToken)
    }

    @Test
    fun switchingNeverPublishesAReadyNewRevisionForTheDepartingProfile() = runBlocking {
        provider.ensureInitialized()
        val original = provider.sessionToken
        val other = provider.createProfile("Other", "", "Other icons")
        val states = Collections.synchronizedList(mutableListOf<ApplicationRuntimeState>())
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            provider.runtimeState.collect { states.add(it) }
        }
        try {
            provider.switchProfile(other)
        } finally {
            observer.cancelAndJoin()
        }
        val invalidated = states.filter {
            it.sessionToken.revision > original.revision && it.activeProfileId == original.profileId
        }
        assertTrue(invalidated.isNotEmpty())
        assertTrue(invalidated.all { !it.ready })
        assertTrue(states.any { it.activeProfileId == other && !it.ready })
        val completed = provider.runtimeState.value
        assertTrue(completed.ready)
        assertEquals(other, completed.activeProfileId)
        assertEquals(original.providerInstanceId, completed.sessionToken.providerInstanceId)
        assertEquals(completed.sessionToken, provider.applicationState.value.sessionToken)
    }

    @Test
    fun deletingTheActiveProfilePublishesTheDefaultOwnerAndExpiresTheDeletedOwner() = runBlocking {
        provider.ensureInitialized()
        val other = provider.createProfile("Temporary", "", "Temporary icons")
        provider.switchProfile(other)
        val deleted = provider.sessionToken

        // Robolectric resets SQLite pointers between tests, but the production singleton survives.
        val watchInstance = WatchDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        watchInstance.set(null, null)
        try {
            provider.deleteProfile(other)
        } finally {
            (watchInstance.get(null) as? WatchDatabase)?.close()
            watchInstance.set(null, null)
        }

        val restored = provider.runtimeState.value
        assertTrue(restored.ready)
        assertEquals(DEFAULT_PROFILE_ID, restored.activeProfileId)
        assertTrue(restored.sessionToken.revision > deleted.revision)
        assertEquals(deleted.providerInstanceId, restored.sessionToken.providerInstanceId)
        assertEquals(restored.sessionToken, provider.applicationState.value.sessionToken)
        assertFalse(provider.profileExists(other))
        assertFalse(provider.isCurrentSession(deleted))
    }

    @Test
    fun runtimeTransitionPublishesBeforeTheCompletedApplicationSnapshot() = runBlocking {
        provider.ensureInitialized()
        val original = provider.applicationState.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            provider.runtimeState.first { it.isProfileSwitching }
        }
        val replacing = launch {
            provider.replaceProfileSession { entered.complete(Unit); release.await() }
        }
        try {
            withTimeout(5_000) { entered.await() }
            val running = withTimeout(5_000) { transition.await() }
            assertTrue(running.startupComplete)
            assertFalse(running.ready)
            assertSame(original, provider.applicationState.value)
            assertFalse(provider.isCurrentSession(provider.sessionToken))
        } finally {
            release.complete(Unit)
            replacing.join()
        }
        assertTrue(provider.runtimeState.value.ready)
        assertEquals(provider.sessionToken, provider.applicationState.value.sessionToken)
        assertNotEquals(original.sessionToken, provider.applicationState.value.sessionToken)
    }

    @Test
    fun cancelledSessionReplacementRestoresRuntimeReadiness() = runBlocking {
        provider.ensureInitialized()
        val entered = CompletableDeferred<Unit>()
        val replacing = launch {
            provider.replaceProfileSession { entered.complete(Unit); awaitCancellation() }
        }
        try {
            withTimeout(5_000) { entered.await() }
            assertTrue(provider.runtimeState.value.isProfileSwitching)
        } finally {
            replacing.cancelAndJoin()
        }
        assertTrue(provider.runtimeState.value.ready)
        assertTrue(provider.isCurrentSession(provider.sessionToken))
        assertEquals(provider.sessionToken, provider.applicationState.value.sessionToken)
    }

    @Test
    fun loadingRejectionDoesNotPretendTheProfileChanged() = runBlocking {
        var wrote = false
        expectFailure(ProfileSessionFailureReason.LOADING) {
            provider.updateProfilePreferences(provider.sessionToken) { wrote = true }
        }
        assertFalse(wrote)
    }

    @Test
    fun leavingAndReturningToTheSameProfileDoesNotReviveItsToken() = runBlocking {
        provider.ensureInitialized()
        val original = provider.sessionToken
        val other = provider.createProfile("Other", "", "Other icons")
        provider.switchProfile(other)
        val otherOwner = provider.runtimeState.value.sessionToken
        provider.switchProfile(original.profileId)

        assertEquals(original.profileId, provider.activeProfileId)
        assertNotEquals(original, provider.sessionToken)
        assertTrue(otherOwner.revision > original.revision)
        assertTrue(provider.sessionToken.revision > otherOwner.revision)
        assertEquals(original.providerInstanceId, provider.sessionToken.providerInstanceId)
        assertEquals(provider.sessionToken, provider.runtimeState.value.sessionToken)
        var wrote = false
        expectFailure(ProfileSessionFailureReason.EXPIRED) {
            provider.updateProfilePreferences(original) { wrote = true }
        }
        assertFalse(wrote)
        provider.updateProfilePreferences(provider.sessionToken) { wrote = true }
        assertTrue(wrote)
    }

    @Test
    fun anotherProviderInstanceCannotUseAnOldEditorToken() = runBlocking {
        provider.ensureInitialized()
        val original = provider.sessionToken
        val restarted = newProvider()
        restarted.ensureInitialized()
        assertEquals(original.profileId, restarted.activeProfileId)
        assertNotEquals(original.providerInstanceId, restarted.runtimeState.value.sessionToken.providerInstanceId)
        assertTrue(restarted.runtimeState.value.ready)
        expectFailure(ProfileSessionFailureReason.PROCESS_LOST) {
            restarted.updateProfilePreferences(original) { fail("Old editor must not write") }
        }
    }

    @Test
    fun expiredBookkeepingIsCheckedAfterAcquiringTheGate() = runBlocking {
        provider.ensureInitialized()
        val original = provider.sessionToken
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacing = launch {
            provider.replaceProfileSession { started.complete(Unit); release.await() }
        }
        started.await()
        var wrote = false
        val bookkeeping = async(start = CoroutineStart.UNDISPATCHED) {
            provider.commitSessionState(original) { wrote = true }
        }
        assertFalse(bookkeeping.isCompleted)
        release.complete(Unit)
        replacing.join()
        assertFalse(bookkeeping.await())
        assertFalse(wrote)
    }

    @Test
    fun reloadFailureReportsActualRestoredSessionAndExpiresOldActions() = runBlocking {
        provider.ensureInitialized()
        val original = provider.sessionToken
        val apps = provider.applicationList.toList()
        context.failDiscovery = true
        try {
            provider.reloadPreservingSession(original, emptySet())
            fail("Expected discovery failure")
        } catch (error: ProfileReloadFailedException) {
            assertEquals(provider.sessionToken, error.restoredSession)
            assertEquals(error.restoredSession, provider.runtimeState.value.sessionToken)
            assertNotEquals(original, error.restoredSession)
            assertEquals(apps, provider.applicationList)
            assertFalse(provider.isProfileSwitching)
            assertTrue(provider.runtimeState.value.ready)
            assertTrue(provider.isCurrentSession(error.restoredSession))
            assertSame(IllegalStateException::class.java, error.cause!!.javaClass)
        }
        expectFailure(ProfileSessionFailureReason.EXPIRED) {
            provider.saveActiveProfileIcons(original)
        }
    }

    @Test
    fun stalePreviewCannotStartApkAssembly() = runBlocking {
        provider.ensureInitialized()
        val reviewed = provider.sessionToken
        val prefs = context.dataStore.data.first()
        provider.clearIcons(reviewed)
        assertEquals(reviewed.profileId, provider.runtimeState.value.activeProfileId)
        assertTrue(provider.runtimeState.value.sessionToken.revision > reviewed.revision)
        assertTrue(provider.runtimeState.value.ready)
        var buildStarted = false
        expectFailure(ProfileSessionFailureReason.EXPIRED) {
            provider.buildAndSignIconPack(reviewed, prefs, { buildStarted = true })
        }
        assertFalse(buildStarted)
    }

    @Test
    fun clearingIconsMarksTheProfileAsWaitingForABuild() = runBlocking {
        provider.ensureInitialized()
        val profileId = provider.sessionToken.profileId
        repository.updateProfile(requireNotNull(repository.profile(profileId)).copy(hasUnbuiltChanges = false))

        provider.clearIcons(provider.sessionToken)

        assertTrue(requireNotNull(repository.profile(profileId)).hasUnbuiltChanges)
    }

    private val viewModelStores = mutableListOf<ViewModelStore>()

    private fun globalOptionsViewModel(handle: SavedStateHandle = SavedStateHandle()) =
        GlobalOptionsViewModel(RuntimeEnvironment.getApplication(), provider, handle).also { vm ->
            viewModelStores += ViewModelStore().also { it.put("global", vm) }
        }

    private suspend fun awaitMain(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    @After
    fun clearViewModels() {
        viewModelStores.forEach { it.clear() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun globalSaveRunsInTheViewModelAndMovesTheBaseline() = runBlocking {
        provider.ensureInitialized()
        val vm = globalOptionsViewModel()
        val events = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvents.collect { events += it } }
        try {
            awaitMain { vm.baseline != null }
            vm.draft.applyExisting = !vm.draft.applyExisting
            assertTrue(vm.isDirty)

            assertNotNull(vm.saveGlobalStyle())
            awaitMain { !vm.isDirty && vm.globalApplyProgress == null }

            assertEquals(vm.draft.applyExisting, context.dataStore.data.first()[GlobalApplyExistingKey])
            awaitMain { R.string.globalOptionsApplied in events }
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun globalDraftAndBaselineSurviveProcessRecreation() = runBlocking {
        provider.ensureInitialized()
        val handle = SavedStateHandle()
        val vm = globalOptionsViewModel(handle)
        awaitMain { vm.baseline != null }
        val baseline = vm.baseline
        vm.draft.applyExisting = !vm.draft.applyExisting
        val staged = vm.draft.snapshot()

        val restored = globalOptionsViewModel(SavedStateHandle.createHandle(handle.savedStateProvider().saveState(), null))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(staged, restored.draft.snapshot())
        assertEquals(baseline, restored.baseline)
        assertTrue(restored.isDirty)
    }

    @Test
    fun globalSaveAfterTheProfileSessionChangedKeepsTheOldBaseline() = runBlocking {
        provider.ensureInitialized()
        val vm = globalOptionsViewModel()
        awaitMain { vm.baseline != null }
        val baseline = vm.baseline
        vm.draft.applyExisting = !vm.draft.applyExisting
        provider.clearIcons(provider.sessionToken)

        vm.saveGlobalStyle()?.let { job -> awaitMain { !job.isActive } }

        assertEquals(baseline, vm.baseline)
        assertTrue(vm.isDirty)
        assertFalse(context.dataStore.data.first()[GlobalApplyExistingKey] == vm.draft.applyExisting)
    }

    private suspend fun pausedGlobalSave(
        started: CompletableDeferred<Unit>, release: CountDownLatch
    ) {
        val prefs = context.dataStore.data.first()
        provider.applyGlobalModifiers(
            provider.sessionToken, prefs, GenerationOptions.fromPreferences(prefs, context),
            applyGenerated = true, applyExisting = true, applyCustom = true, includeEmpty = false
        ) { done, _ ->
            if (done == 0) {
                started.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release render" }
            }
        }
    }

    @Test
    fun longRenderRejectsRecipeWritesAndAnotherBulkOperationImmediately() = runBlocking {
        provider.ensureInitialized()
        val idle = provider.applicationState.value
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val saving = async { pausedGlobalSave(started, release) }
        try {
            withTimeout(5_000) { started.await() }
            assertTrue(provider.longOperationRunning)
            val busy = provider.applicationState.value
            assertTrue(busy.longOperationRunning)
            assertSame(idle.applications, busy.applications)
            assertEquals(idle.revision + 1, busy.revision)
            assertFalse(idle.longOperationRunning)
            var wrote = false
            try {
                withTimeout(1_000) {
                    provider.updateProfilePreferences(provider.sessionToken) { wrote = true }
                }
                fail("Recipe write must be refused during render")
            } catch (_: ProfileOperationBusyException) { }
            assertFalse(wrote)
            try {
                withTimeout(1_000) { provider.refreshIcons(provider.sessionToken) }
                fail("Refresh must not invalidate Global Save")
            } catch (_: ProfileOperationBusyException) { }
        } finally {
            release.countDown()
        }
        saving.await()
        assertFalse(provider.longOperationRunning)
        assertFalse(provider.applicationState.value.longOperationRunning)
        provider.updateProfilePreferences(provider.sessionToken) { }
    }

    @Test
    fun cancelledRenderReleasesItsLeaseAndCannotCommitPreferences() = runBlocking {
        provider.ensureInitialized()
        val before = context.dataStore.data.first()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val saving = launch { pausedGlobalSave(started, release) }
        try {
            withTimeout(5_000) { started.await() }
            saving.cancel()
        } finally {
            release.countDown()
        }
        saving.join()
        assertFalse(provider.longOperationRunning)
        assertFalse(provider.applicationState.value.longOperationRunning)
        assertEquals(before, context.dataStore.data.first())
        var wrote = false
        provider.updateProfilePreferences(provider.sessionToken) { wrote = true }
        assertTrue(wrote)
    }

    @Test
    fun switchingDuringRenderLetsNewSessionWriteAndRejectsOldSave() = runBlocking {
        provider.ensureInitialized()
        val original = provider.sessionToken
        val other = provider.createProfile("Other", "", "Other icons")
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val saving = launch {
            expectFailure(ProfileSessionFailureReason.EXPIRED) { pausedGlobalSave(started, release) }
        }
        try {
            withTimeout(5_000) { started.await() }
            provider.switchProfile(other)
            assertFalse(provider.longOperationRunning)
            assertEquals(provider.sessionToken, provider.applicationState.value.sessionToken)
            assertFalse(provider.applicationState.value.longOperationRunning)
            assertNotEquals(original, provider.sessionToken)
            var wrote = false
            provider.updateProfilePreferences(provider.sessionToken) { wrote = true }
            assertTrue(wrote)
        } finally {
            release.countDown()
        }
        saving.join()
        assertEquals(other, provider.activeProfileId)
        assertFalse(provider.applicationState.value.longOperationRunning)
    }

}
