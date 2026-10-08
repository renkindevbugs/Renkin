package dev.renkinProject.renkin.apk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileOperationGateTest {

    private suspend fun expectNoOwner(gate: ProfileOperationGate) {
        try {
            gate.checkHeld()
            fail("Expected a missing gate owner")
        } catch (_: IllegalStateException) { }
    }

    @Test
    fun onlyTheActiveOwnerPassesAcrossDispatcherChanges() = runBlocking {
        val gate = ProfileOperationGate()
        val other = ProfileOperationGate()
        expectNoOwner(gate)
        gate.run {
            gate.checkHeld()
            withContext(Dispatchers.Default) { gate.checkHeld() }
            expectNoOwner(other)
        }
        gate.runWithBoundedWait { gate.checkHeld() }
        expectNoOwner(gate)
    }

    @Test
    fun aLockedGateDoesNotAuthorizeAnotherCoroutineOrAnExpiredMarker() = runBlocking {
        val gate = ProfileOperationGate()
        val entered = CompletableDeferred<CoroutineContext>()
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                entered.complete(currentCoroutineContext())
                release.await()
            }
        }
        val captured = entered.await()
        expectNoOwner(gate)
        release.complete(Unit)
        owner.join()
        withContext(captured.minusKey(kotlinx.coroutines.Job)) { expectNoOwner(gate) }
    }

    @Test
    fun completionPublishesAfterSuccessFailureAndCancellationButNotRejectedWaiters() = runBlocking {
        var publications = 0
        val gate = ProfileOperationGate { publications++ }
        gate.run { }
        try {
            gate.runWithBoundedWait { throw IllegalArgumentException("failure") }
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, publications)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { awaitCancellation() } }
        val rejected = launch(start = CoroutineStart.UNDISPATCHED) { gate.runWithBoundedWait { fail("Cancelled waiter entered") } }
        rejected.cancelAndJoin()
        assertEquals(2, publications)
        owner.cancelAndJoin()
        assertEquals(3, publications)
        gate.run { }
        assertEquals(4, publications)
    }

    @Test
    fun boundedWaitAllowsShortCriticalSectionToFinish() = runBlocking {
        val gate = ProfileOperationGate()
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { release.await() } }
        val next = async(start = CoroutineStart.UNDISPATCHED) { gate.runWithBoundedWait { 42 } }

        assertFalse(next.isCompleted)
        release.complete(Unit)
        assertEquals(42, next.await())
        owner.join()
    }

    @Test
    fun longOperationRejectsWriteInsteadOfReplayingItLater() = runBlocking {
        val gate = ProfileOperationGate()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { awaitCancellation() } }
        var wrote = false
        try {
            gate.runWithBoundedWait { wrote = true }
            fail("Expected a busy profile operation")
        } catch (_: ProfileOperationBusyException) {
            assertFalse(wrote)
        } finally {
            owner.cancelAndJoin()
        }
        gate.run { assertFalse(wrote) }
    }

    @Test
    fun acquisitionTimeoutDoesNotCancelAnAcceptedWrite() = runBlocking {
        val gate = ProfileOperationGate()
        var completed = false
        gate.runWithBoundedWait {
            delay(350L)
            completed = true
        }
        assertTrue(completed)
        assertEquals(42, gate.runWithBoundedWait { 42 })
    }

    @Test
    fun cancelledBoundedWaiterDoesNotStrandTheGate() = runBlocking {
        val gate = ProfileOperationGate()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { awaitCancellation() } }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runWithBoundedWait { fail("Cancelled waiter must not run") }
        }
        waiter.cancelAndJoin()
        owner.cancelAndJoin()
        assertEquals(42, gate.runWithBoundedWait { 42 })
    }

    @Test
    fun concurrentProfileOperationsRunInOrder() = runBlocking {
        val gate = ProfileOperationGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                events += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstStarted.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run { events += "second" }
        }

        assertFalse(second.isCompleted)
        assertEquals(listOf("first-start"), events)

        releaseFirst.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("first-start", "first-end", "second"), events)
    }

    @Test
    fun cancellingProfileOperationReleasesNextOperation() = runBlocking {
        val gate = ProfileOperationGate()
        val firstStarted = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
        }
        firstStarted.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run { secondEntered.complete(Unit) }
        }

        assertFalse(secondEntered.isCompleted)
        first.cancelAndJoin()
        second.join()
        assertEquals(Unit, secondEntered.await())
    }
}
