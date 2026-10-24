package dev.renkinProject.renkin.feature.watch

import android.app.Application
import android.graphics.Bitmap
import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.watch.*
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchApplyOwnerTest {
    private fun request(id: Long = 1, suggestionId: Long = 3): WatchApplyRequest {
        val icon = BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        val app = PackageInfoStruct("Test", "test.app", "Main", icon, 0)
        val suggestion = IconSuggestion(suggestionId, 1, app.packageName, app.activityName, 100)
        val candidate = IconSuggestionCandidate(suggestionId, "test.pack", "drawable", "hash")
        val details = RuleWithDetails(WatchRule(id = 1, completed = true, profileId = 2, createdAt = 10),
            listOf(WatchRuleApp(1, app.packageName, app.activityName)), emptyList(), listOf(suggestion))
        return WatchApplyRequest(id, ProfileSessionToken(2, 1, "provider"),
            WatchApplySnapshot(details, suggestion, listOf(candidate), 0), candidate, app, icon)
    }

    @Test fun duplicateClaimIsRejectedAndSuccessWaitsForCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var writes = 0
        val owner = WatchApplyOwner(backgroundScope, { _, ack -> writes++; ack(); IconApplyOutcome.APPLIED },
            { cleanup.await() }, { _, _ -> })
        val request = request()
        assertTrue(owner.submit(request))
        assertFalse(owner.submit(request.copy(id = 2)))
        runCurrent()
        assertEquals(1, writes)
        assertEquals(WatchApplyPhase.CLEANING, owner.states.value[3]?.phase)
        assertFalse(owner.consume(request.id, 3))
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(WatchApplyPhase.APPLIED_CLEANED, owner.states.value[3]?.phase)
        assertFalse(owner.consume(2, 3))
        assertTrue(owner.consume(1, 3))
    }

    @Test fun cleanupFailureRetriesOnlyCleanupAndKeepsTheCapturedRule() = runTest {
        var writes = 0
        var cleanups = 0
        val captured = request()
        val owner = WatchApplyOwner(backgroundScope, { _, ack -> writes++; ack(); IconApplyOutcome.APPLIED },
            { value -> assertSame(captured, value); if (++cleanups == 1) error("Cleanup unavailable") }, { _, _ -> })
        owner.submit(captured)
        runCurrent()
        assertEquals(WatchApplyPhase.APPLIED_CLEANUP_FAILED, owner.states.value[3]?.phase)
        assertFalse(owner.consume(1, 3))
        assertFalse(owner.submit(captured.copy(id = 2)))
        assertFalse(owner.retryCleanup(2, 3))
        assertTrue(owner.retryCleanup(1, 3))
        assertFalse(owner.retryCleanup(1, 3))
        runCurrent()
        assertEquals(1, writes)
        assertEquals(2, cleanups)
        assertEquals(WatchApplyPhase.APPLIED_CLEANED, owner.states.value[3]?.phase)
    }

    @Test fun originalJobCompletionCannotOverwriteAnImmediatelyStartedCleanupRetry() = runTest {
        var cleanupCount = 0
        val retryRelease = CompletableDeferred<Unit>()
        lateinit var owner: WatchApplyOwner
        owner = WatchApplyOwner(backgroundScope, { _, ack -> ack(); IconApplyOutcome.APPLIED }, {
            if (++cleanupCount == 1) error("First cleanup failed")
            retryRelease.await()
        }, { state, _ -> if (state.phase == WatchApplyPhase.APPLIED_CLEANUP_FAILED) owner.retryCleanup(state.request.id, 3) })
        owner.submit(request())
        runCurrent()
        assertEquals(WatchApplyPhase.CLEANING, owner.states.value[3]?.phase)
        assertEquals(1L, owner.states.value[3]?.cleanupAttempt)
        retryRelease.complete(Unit)
        runCurrent()
        assertEquals(WatchApplyPhase.APPLIED_CLEANED, owner.states.value[3]?.phase)
    }

    @Test fun cancellationAfterCommitRetainsCleanupOnlyRetry() = runTest {
        var writes = 0
        var cleanups = 0
        val owner = WatchApplyOwner(backgroundScope, { _, ack ->
            writes++; ack(); throw CancellationException("Return cancelled after commit")
        }, { cleanups++ }, { _, _ -> })
        owner.submit(request())
        runCurrent()
        assertEquals(WatchApplyPhase.APPLIED_CLEANUP_FAILED, owner.states.value[3]?.phase)
        owner.retryCleanup(1, 3)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(1, cleanups)
        assertEquals(WatchApplyPhase.APPLIED_CLEANED, owner.states.value[3]?.phase)
    }

    @Test fun cancellationBeforeCommitKeepsTheSuggestionAndNeverCleansIt() = runTest {
        var cleanups = 0
        val owner = WatchApplyOwner(backgroundScope, { _, _ -> awaitCancellation() }, { cleanups++ }, { _, _ -> })
        owner.submit(request())
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertEquals(0, cleanups)
        assertEquals(WatchApplyPhase.NOT_APPLIED, owner.states.value[3]?.phase)
    }

    @Test fun cancellationBeforeStartReleasesClaimWithoutCleanup() = runTest {
        val owner = WatchApplyOwner(backgroundScope, { _, _ -> error("Must not run") }, { error("Must not clean") }, { _, _ -> })
        owner.submit(request())
        backgroundScope.cancel()
        runCurrent()
        assertEquals(WatchApplyPhase.NOT_APPLIED, owner.states.value[3]?.phase)
    }

    @Test fun providerRejectionDoesNotRemoveTheRule() = runTest {
        var cleanups = 0
        val owner = WatchApplyOwner(backgroundScope, { _, _ -> IconApplyOutcome.LOCKED }, { cleanups++ }, { _, _ -> })
        owner.submit(request())
        runCurrent()
        assertEquals(0, cleanups)
        assertEquals(IconApplyOutcome.LOCKED, owner.states.value[3]?.outcome)
        assertEquals(WatchApplyPhase.NOT_APPLIED, owner.states.value[3]?.phase)
    }

    @Test fun thrownApplyFailureDoesNotPretendItCommitted() = runTest {
        var failures = 0
        val owner = WatchApplyOwner(backgroundScope, { _, _ -> error("Apply failed") }, { error("Must not clean") },
            { _, error -> assertNotNull(error); failures++ })
        owner.submit(request())
        runCurrent()
        assertEquals(1, failures)
        assertEquals(WatchApplyPhase.NOT_APPLIED, owner.states.value[3]?.phase)
    }

    @Test fun differentSuggestionsKeepSeparateRetainedResults() = runTest {
        val owner = WatchApplyOwner(backgroundScope, { _, ack -> ack(); IconApplyOutcome.APPLIED }, { }, { _, _ -> })
        owner.submit(request(1, 3))
        owner.submit(request(2, 4))
        runCurrent()
        assertEquals(2, owner.states.value.size)
        assertFalse(owner.consume(1, 4))
        assertTrue(owner.consume(2, 4))
        assertEquals(setOf(3L), owner.states.value.keys)
    }

    @Test fun restoredStorageCanReplaceAnObsoleteResultWithoutRetryingItsCleanup() = runTest {
        var writes = 0
        val owner = WatchApplyOwner(backgroundScope, { _, ack -> writes++; ack(); IconApplyOutcome.APPLIED },
            { if (it.snapshot.storageEpoch == 0L) error("Old storage") }, { _, _ -> })
        val old = request()
        owner.submit(old)
        runCurrent()
        assertTrue(owner.submit(old.copy(id = 2, snapshot = old.snapshot.copy(storageEpoch = 1))))
        runCurrent()
        assertEquals(2, writes)
        assertFalse(owner.retryCleanup(1, 3))
        assertEquals(WatchApplyPhase.APPLIED_CLEANED, owner.states.value[3]?.phase)
    }
}
