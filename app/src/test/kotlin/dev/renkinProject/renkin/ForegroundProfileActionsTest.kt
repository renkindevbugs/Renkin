package dev.renkinProject.renkin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundProfileActionsTest {
    private val reset = ProfileActionProgress(R.string.iconResetInProgress, "Example")

    @Test fun claimIsSynchronousAndDuplicateDoesNotStartAnotherJob() = runTest {
        val mutex = Mutex()
        var busy = 0
        val actions = ForegroundProfileActions(mutex) { busy++ }
        val job = launch { awaitCancellation() }
        actions.launch(reset, true) { job }
        assertEquals(reset, actions.progress.value)
        assertTrue(mutex.isLocked)
        actions.launch(reset, false) { fail("Duplicate started"); Job() }
        assertEquals(1, busy)
        job.cancel()
        runCurrent()
        assertNull(actions.progress.value)
        assertFalse(mutex.isLocked)
        actions.launch(reset, true) { Job().apply { complete() } }
        assertNull(actions.progress.value)
        assertFalse(mutex.isLocked)
    }

    @Test fun contendedSessionLockRejectsWithoutCallingStartOrReleasingItsOwner() {
        val mutex = Mutex(locked = true)
        var busy = 0
        val actions = ForegroundProfileActions(mutex) { busy++ }
        actions.launch(reset, true) { fail("Contended action started"); Job() }
        assertEquals(1, busy)
        assertNull(actions.progress.value)
        assertTrue(mutex.isLocked)
        mutex.unlock()
    }

    @Test fun cancellationBeforeEntryReleasesProgressAndSessionLock() = runTest {
        val mutex = Mutex()
        val actions = ForegroundProfileActions(mutex) { fail("Unexpected busy") }
        val owner = SupervisorJob().apply { cancel() }
        var entered = false
        actions.launch(reset, true) {
            CoroutineScope(coroutineContext + owner).launch { entered = true }
        }
        runCurrent()
        assertFalse(entered)
        assertFalse(mutex.isLocked)
        assertNull(actions.progress.value)
    }

    @Test fun failedJobAndThrowingStartBothReleaseReservations() {
        val mutex = Mutex()
        val actions = ForegroundProfileActions(mutex) { fail("Unexpected busy") }
        val job = Job()
        actions.launch(reset, true) { job }
        job.completeExceptionally(IllegalArgumentException("work failed"))
        assertNull(actions.progress.value)
        assertFalse(mutex.isLocked)
        assertThrows(IllegalArgumentException::class.java) {
            actions.launch(reset, true) { throw IllegalArgumentException("start failed") }
        }
        assertNull(actions.progress.value)
        assertFalse(mutex.isLocked)
    }

    @Test fun unserializedGenerateNeverUnlocksAnotherOperationsMutex() {
        val mutex = Mutex(locked = true)
        val actions = ForegroundProfileActions(mutex) { fail("Unexpected busy") }
        val job = Job()
        actions.launch(ProfileActionProgress(R.string.iconGenerationInProgress), false) { job }
        assertNotNull(actions.progress.value)
        job.cancel()
        assertNull(actions.progress.value)
        assertTrue(mutex.isLocked)
        mutex.unlock()
    }
}
