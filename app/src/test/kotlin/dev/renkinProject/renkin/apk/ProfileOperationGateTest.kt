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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileOperationGateTest {

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
