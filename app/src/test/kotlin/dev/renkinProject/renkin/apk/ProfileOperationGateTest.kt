package dev.renkinProject.renkin.apk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileOperationGateTest {

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
