package dev.renkinProject.renkin.util

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A user action that fails must reach the user through its callback instead of the uncaught
 * handler (which ends the process), while cancellation keeps propagating untouched.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LaunchGuardedTest {

    @Test
    fun failureReachesTheCallbackOnceAndTheScopeSurvives() = runTest {
        val failures = mutableListOf<Exception>()

        launchGuarded(onFailure = { failures += it }) { throw IOException("disk full") }
        advanceUntilIdle()

        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
        assertTrue(isActive)
        var ranAfterwards = false
        launchGuarded(onFailure = { failures += it }) { ranAfterwards = true }
        advanceUntilIdle()
        assertTrue(ranAfterwards)
        assertEquals(1, failures.size)
    }

    @Test
    fun successNeverCallsTheFailureCallback() = runTest {
        val failures = mutableListOf<Exception>()
        var done = false

        launchGuarded(onFailure = { failures += it }) { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun cancellingTheJobIsNotReportedAsAFailure() = runTest {
        val failures = mutableListOf<Exception>()

        val job = launchGuarded(onFailure = { failures += it }) { awaitCancellation() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun cancellationThrownByTheActionPropagatesInsteadOfBeingReported() = runTest {
        val failures = mutableListOf<Exception>()

        val job = launchGuarded(onFailure = { failures += it }) { throw CancellationException("stopped") }
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertTrue(failures.isEmpty())
    }
}
