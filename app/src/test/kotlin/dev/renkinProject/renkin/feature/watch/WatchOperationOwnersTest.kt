package dev.renkinProject.renkin.feature.watch

import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.watch.AppComponent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchOperationOwnersTest {
    private fun request(id: Long) = WatchRuleSaveRequest(
        id = id,
        session = ProfileSessionToken(1, 1, "provider"),
        profileId = 1,
        existingRuleId = null,
        apps = listOf(AppComponent("test.app", "Main")),
        watchAllPacks = true,
        packs = emptyList()
    )

    @Test fun duplicateSaveClaimIsRejectedAndTheOutcomeWaitsForItsOwnAck() = runTest {
        val gate = CompletableDeferred<WatchRuleSaveStatus>()
        var saves = 0
        val finished = mutableListOf<WatchRuleSaveState>()
        val owner = WatchRuleSaveOwner(backgroundScope, { saves++; gate.await() }) { state, _, _ -> finished += state }

        assertTrue(owner.submit(request(1)))
        assertFalse(owner.submit(request(2)))
        runCurrent()
        assertEquals(1, saves)
        assertEquals(WatchRuleSaveState(1, WatchRuleSaveStatus.SAVING), owner.state.value)
        assertFalse(owner.acknowledge(1))

        gate.complete(WatchRuleSaveStatus.SAVED)
        runCurrent()
        // Retained with no collector until the matching opener acknowledges it.
        assertEquals(WatchRuleSaveState(1, WatchRuleSaveStatus.SAVED), owner.state.value)
        assertEquals(listOf(WatchRuleSaveState(1, WatchRuleSaveStatus.SAVED)), finished)
        assertFalse(owner.acknowledge(2))
        assertEquals(WatchRuleSaveStatus.SAVED, owner.state.value?.status)
        assertTrue(owner.acknowledge(1))
        assertNull(owner.state.value)
    }

    @Test fun aNewEditorSaveReplacesAnUnacknowledgedOutcomeAndTheOldIdCannotClearIt() = runTest {
        val owner = WatchRuleSaveOwner(backgroundScope, { WatchRuleSaveStatus.SAVED }) { _, _, _ -> }
        assertTrue(owner.submit(request(1)))
        runCurrent()
        assertEquals(WatchRuleSaveState(1, WatchRuleSaveStatus.SAVED), owner.state.value)

        assertTrue(owner.submit(request(2)))
        runCurrent()
        assertFalse(owner.acknowledge(1))
        assertEquals(WatchRuleSaveState(2, WatchRuleSaveStatus.SAVED), owner.state.value)
    }

    @Test fun failedSaveIsReportedWithItsErrorAndTheSameEditorCanRetry() = runTest {
        val failure = IOException("pack unreadable")
        var attempt = 0
        val reported = mutableListOf<Pair<WatchRuleSaveState, Exception?>>()
        val owner = WatchRuleSaveOwner(backgroundScope, {
            if (attempt++ == 0) throw failure else WatchRuleSaveStatus.SAVED
        }) { state, _, error -> reported += state to error }

        assertTrue(owner.submit(request(7)))
        runCurrent()
        assertEquals(WatchRuleSaveState(7, WatchRuleSaveStatus.NOT_SAVED), owner.state.value)
        assertFalse(owner.state.value!!.busy)
        assertSame(failure, reported.single().second)

        assertTrue(owner.submit(request(7)))
        runCurrent()
        assertEquals(WatchRuleSaveState(7, WatchRuleSaveStatus.SAVED), owner.state.value)
        assertNull(reported.last().second)
    }

    @Test fun sessionChangeIsReportedWithTheCapturedRequest() = runTest {
        var reportedRequest: WatchRuleSaveRequest? = null
        val owner = WatchRuleSaveOwner(backgroundScope, { WatchRuleSaveStatus.SESSION_CHANGED }) { _, request, _ ->
            reportedRequest = request
        }
        val captured = request(3)
        assertTrue(owner.submit(captured))
        runCurrent()
        assertEquals(WatchRuleSaveStatus.SESSION_CHANGED, owner.state.value?.status)
        assertSame(captured, reportedRequest)
    }

    @Test fun cancelledSaveDoesNotStayBusy() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val owner = WatchRuleSaveOwner(scope, { awaitCancellation() }) { _, _, _ -> }
        assertTrue(owner.submit(request(1)))
        runCurrent()
        assertTrue(owner.state.value!!.busy)
        scope.cancel()
        runCurrent()
        assertEquals(WatchRuleSaveState(1, WatchRuleSaveStatus.NOT_SAVED), owner.state.value)
        assertFalse(owner.submit(request(2)))
    }

    @Test fun duplicateCheckClaimIsRejectedAndTheResultIsReportedOnce() = runTest {
        val gate = CompletableDeferred<Int?>()
        var checks = 0
        val results = mutableListOf<Pair<Int?, Exception?>>()
        val owner = WatchCheckOwner(backgroundScope, { checks++; gate.await() }) { found, error -> results += found to error }

        assertTrue(owner.start())
        assertTrue(owner.running.value)
        assertFalse(owner.start())
        runCurrent()
        assertEquals(1, checks)

        gate.complete(4)
        runCurrent()
        assertFalse(owner.running.value)
        assertEquals(listOf<Pair<Int?, Exception?>>(4 to null), results)
        assertTrue(owner.start())
    }

    @Test fun failedCheckStopsRunningAndReportsTheErrorInsteadOfACount() = runTest {
        val failure = IOException("checker failed")
        val results = mutableListOf<Pair<Int?, Exception?>>()
        val owner = WatchCheckOwner(backgroundScope, { throw failure }) { found, error -> results += found to error }
        assertTrue(owner.start())
        runCurrent()
        assertFalse(owner.running.value)
        assertNull(results.single().first)
        assertSame(failure, results.single().second)
    }

    @Test fun staleCheckReportsNoCount() = runTest {
        val results = mutableListOf<Pair<Int?, Exception?>>()
        val owner = WatchCheckOwner(backgroundScope, { null }) { found, error -> results += found to error }
        assertTrue(owner.start())
        runCurrent()
        assertEquals(listOf<Pair<Int?, Exception?>>(null to null), results)
    }
}
