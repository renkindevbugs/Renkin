package dev.renkinProject.renkin.apk

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconPackInstallCompletionTest {
    private val installed = ApkInstallOutcome(ApkInstallResult.SUCCESS)

    @Test
    fun publishesSuccessBeforeWaitingForPersistenceButStillAwaitsItsCompletion() = runBlocking {
        val published = AtomicBoolean(false)
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            finishConfirmedInstallation(installed, onInstalled = {
                assertSame(installed, it)
                published.set(true)
            }) {
                assertTrue(published.get())
                saving.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { saving.await() }
            assertTrue(published.get())
            assertFalse(owner.isCompleted)
        } finally {
            release.complete(Unit)
        }
        val completion = owner.await()
        assertSame(installed, completion.outcome)
        assertFalse(completion.profileSaveFailed)
    }

    @Test
    fun savingFailureDoesNotRewriteConfirmedInstallerSuccess() = runBlocking {
        val completion = finishConfirmedInstallation(installed, onInstalled = {}) {
            error("Simulated profile write failure")
        }
        assertSame(installed, completion.outcome)
        assertEquals(ApkInstallResult.SUCCESS, completion.outcome.result)
        assertTrue(completion.profileSaveFailed)
    }

    @Test
    fun publicationFailureStillPersistsTheSnapshot() = runBlocking {
        var persisted = false
        val completion = finishConfirmedInstallation(installed, onInstalled = {
            error("Simulated UI publication failure")
        }) { persisted = true }
        assertTrue(persisted)
        assertFalse(completion.profileSaveFailed)
    }

    @Test
    fun callbackCancellationPropagatesAfterPersistence() = runBlocking {
        var persisted = false
        val cancellation = CancellationException("UI owner closed")
        val failure = runCatching {
            finishConfirmedInstallation(installed, onInstalled = { throw cancellation }) {
                persisted = true
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
        assertTrue(persisted)
    }

    @Test
    fun cancellingTheCallerDoesNotAbandonAnAlreadyConfirmedSave() = runBlocking {
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = AtomicBoolean(false)
        val owner = launch {
            finishConfirmedInstallation(installed, onInstalled = {}) {
                saving.complete(Unit)
                release.await()
                persisted.set(true)
            }
        }
        try {
            withTimeout(5_000) { saving.await() }
            owner.cancel()
            assertFalse(owner.isCompleted)
        } finally {
            release.complete(Unit)
        }
        owner.join()
        assertTrue(persisted.get())
        assertTrue(owner.isCancelled)
    }

    @Test
    fun persistenceCancellationIsNotReportedAsASavingFailure() = runBlocking {
        val cancellation = CancellationException("Storage owner cancelled")
        val failure = runCatching {
            finishConfirmedInstallation(installed, onInstalled = {}) { throw cancellation }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
    }
}
