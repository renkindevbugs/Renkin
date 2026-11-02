package dev.renkinProject.renkin.util

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A best-effort call may swallow its own failure, but a cancelled coroutine must stay cancelled:
 * turning the cancellation into a failure lets the caller carry on and publish a stale result.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunCatchingCancellableTest {

    @Test
    fun successIsReturned() {
        assertEquals(Result.success(7), runCatchingCancellable { 7 })
    }

    @Test
    fun ordinaryFailureIsCaught() {
        val result = runCatchingCancellable<Int> { throw IOException("disk full") }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        runCatchingCancellable<Int> { throw CancellationException("cancelled") }
    }

    @Test
    fun cancelledCoroutineDoesNotContinuePastTheCall() = runTest {
        var continuedAfterCall = false
        val job = launch {
            runCatchingCancellable { awaitCancellation() }
            continuedAfterCall = true
        }
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertFalse(continuedAfterCall)
    }
}
