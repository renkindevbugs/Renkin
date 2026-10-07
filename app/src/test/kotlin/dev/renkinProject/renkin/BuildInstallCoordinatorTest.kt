package dev.renkinProject.renkin

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.InstallerSelection
import dev.renkinProject.renkin.apk.installerSelection
import dev.renkinProject.renkin.data.AskInstallerEveryTimeKey
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.data.InstallerChoiceConfirmedKey
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.di.AppModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BuildInstallCoordinatorTest {
    private lateinit var application: Application
    private lateinit var database: RenkinPackDatabase
    private lateinit var provider: ApplicationProvider
    private val notifications = mutableListOf<Int>()

    @Before
    fun setUp() = runBlocking {
        application = RuntimeEnvironment.getApplication()
        application.dataStore.edit { it.clear() }
        database = Room.inMemoryDatabaseBuilder(application, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        val repository = RenkinPackRepository(database)
        val catalog = AppModule.provideIconPackCatalog(application)
        val icons = AppModule.provideIconPackRepository(application, catalog)
        val locks = AppModule.provideIconLockManager(application, repository)
        val profiles = AppModule.provideProfileManager(application, repository)
        provider = AppModule.provideApplicationProvider(
            application, AppModule.provideRenkinPackStore(application, repository), repository,
            icons, locks, profiles,
            AppModule.provideIconGenerationService(application, icons, catalog),
            AppModule.provideIconPackBuildService(application, icons, repository, locks, profiles, catalog)
        )
    }

    @After
    fun tearDown() { database.close() }

    private fun coordinator(scope: CoroutineScope) = BuildInstallCoordinator(
        application, provider, scope,
        syncBaselines = { error("A rejected build has no baseline to synchronize") },
        refreshMissingPacks = { error("A rejected build must not refresh installed packs") },
        notify = { notifications += it }
    )

    private suspend fun Job.awaitChildren() = withTimeout(5_000) { children.toList().joinAll() }

    @Test
    fun buildClaimsBusyBeforeItsCoroutineAndDuplicateTapIsRejected() = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val coordinator = coordinator(CoroutineScope(coroutineContext + owner))
        try {
            coordinator.build(provider.sessionToken)
            assertTrue(coordinator.busy)
            assertEquals(BuildInstallState.Building(), coordinator.state)
            coordinator.build(provider.sessionToken)
            owner.awaitChildren()
            assertEquals(listOf(R.string.profileStillLoading), notifications)
            assertFalse(coordinator.busy)
            assertEquals(BuildInstallState.Idle, coordinator.state)
        } finally { coordinator.close(); owner.cancelAndJoin() }
    }

    @Test
    fun loadingFailureReleasesTheStateForAnotherBuild() = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val coordinator = coordinator(CoroutineScope(coroutineContext + owner))
        try {
            repeat(2) {
                coordinator.build(provider.sessionToken)
                owner.awaitChildren()
                assertEquals(BuildInstallState.Idle, coordinator.state)
            }
            assertEquals(List(2) { R.string.profileStillLoading }, notifications)
        } finally { coordinator.close(); owner.cancelAndJoin() }
    }

    @Test
    fun closeBeforeTheCoroutineStartsSuppressesLateFailureAndFurtherBuilds() = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val coordinator = coordinator(CoroutineScope(coroutineContext + owner))
        try {
            coordinator.build(provider.sessionToken)
            coordinator.close()
            coordinator.build(provider.sessionToken)
            owner.awaitChildren()
            assertEquals(BuildInstallState.Idle, coordinator.state)
            assertTrue(notifications.isEmpty())
        } finally { owner.cancelAndJoin() }
    }

    @Test
    fun closeCancelsBuildWaitingForStorageWithoutPublishingFailure() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val blocker = launch(start = CoroutineStart.UNDISPATCHED) {
            StorageOperationGate.run { release.await() }
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val coordinator = coordinator(CoroutineScope(coroutineContext + owner))
        try {
            coordinator.build(provider.sessionToken)
            yield()
            assertTrue(coordinator.busy)
            coordinator.close()
            owner.awaitChildren()
            assertEquals(BuildInstallState.Idle, coordinator.state)
            assertTrue(notifications.isEmpty())
        } finally {
            release.complete(Unit)
            blocker.join()
            owner.cancelAndJoin()
        }
        assertEquals(42, StorageOperationGate.run { 42 })
    }

    @Test
    fun staleDialogActionsCannotInterruptABuild() = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val coordinator = coordinator(CoroutineScope(coroutineContext + owner))
        try {
            coordinator.build(provider.sessionToken)
            coordinator.confirmInstallerSelection(InstallerSelection(InstallMethod.SYSTEM))
            coordinator.confirmInstallFallback()
            coordinator.dismissInstallerSelection()
            coordinator.dismissInstallFallback()
            coordinator.dismissBuildOutcome()
            coordinator.dismissInstallFailure()
            assertEquals(BuildInstallState.Building(), coordinator.state)
            owner.awaitChildren()
            assertEquals(listOf(R.string.profileStillLoading), notifications)
        } finally { coordinator.close(); owner.cancelAndJoin() }
    }

    @Test
    fun rememberedSelectionPreservesAskEveryTimeAndReplacesThePreviousRoute() = runBlocking {
        application.dataStore.edit { it[AskInstallerEveryTimeKey] = true }
        val external = InstallerSelection(InstallMethod.EXTERNAL, "example.installer/.InstallActivity")
        application.dataStore.persistInstallerSelection(external)
        var preferences = application.dataStore.getPreferencesAfterPendingWrites()
        assertEquals(external, preferences.installerSelection())
        assertTrue(preferences.getBooleanValue(InstallerChoiceConfirmedKey))
        assertTrue(preferences.getBooleanValue(AskInstallerEveryTimeKey))

        val system = InstallerSelection(InstallMethod.SYSTEM)
        application.dataStore.persistInstallerSelection(system)
        preferences = application.dataStore.getPreferencesAfterPendingWrites()
        assertEquals(system, preferences.installerSelection())
        assertTrue(preferences.getBooleanValue(InstallerChoiceConfirmedKey))
    }
}
