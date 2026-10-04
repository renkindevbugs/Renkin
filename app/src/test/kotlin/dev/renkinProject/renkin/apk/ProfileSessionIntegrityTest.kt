package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.di.AppModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfileSessionIntegrityTest {
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
        return AppModule.provideApplicationProvider(
            context,
            AppModule.provideRenkinPackStore(context, repository),
            repository,
            icons,
            locks,
            profiles,
            AppModule.provideIconGenerationService(context, icons, catalog),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog)
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
        provider.switchProfile(original.profileId)

        assertEquals(original.profileId, provider.activeProfileId)
        assertNotEquals(original, provider.sessionToken)
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
            assertNotEquals(original, error.restoredSession)
            assertEquals(apps, provider.applicationList)
            assertFalse(provider.isProfileSwitching)
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
        var buildStarted = false
        expectFailure(ProfileSessionFailureReason.EXPIRED) {
            provider.buildAndSignIconPack(reviewed, prefs, { buildStarted = true })
        }
        assertFalse(buildStarted)
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
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val saving = async { pausedGlobalSave(started, release) }
        try {
            withTimeout(5_000) { started.await() }
            assertTrue(provider.longOperationRunning)
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
            assertNotEquals(original, provider.sessionToken)
            var wrote = false
            provider.updateProfilePreferences(provider.sessionToken) { wrote = true }
            assertTrue(wrote)
        } finally {
            release.countDown()
        }
        saving.join()
        assertEquals(other, provider.activeProfileId)
    }

}
