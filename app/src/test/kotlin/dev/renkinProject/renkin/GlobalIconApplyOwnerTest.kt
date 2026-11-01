package dev.renkinProject.renkin

import android.app.Application
import android.graphics.Bitmap
import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalIconApplyOwnerTest {
    private fun request(): GlobalIconApplyRequest {
        val icon = BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        val app = PackageInfoStruct("Test", "test.app", "Main", icon, 0)
        val options = GenerationOptions(IconSourceOptions(Source.APPLICATION_ICON),
            ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = -1))), IconExportOptions(), true)
        return GlobalIconApplyRequest(GlobalIconApplyId(1, 1, ProfileSessionToken(1, 1, "provider"), app.key, 2),
            app, options, icon, null)
    }

    @Test fun synchronousClaimRejectsSecondTapBeforeTheCoroutineStarts() = runTest {
        val release = CompletableDeferred<Unit>()
        var commits = 0
        val owner = GlobalIconApplyOwner(backgroundScope, { _, acknowledge ->
            release.await(); commits++; acknowledge(); IconApplyOutcome.APPLIED
        }, { _, _ -> fail("Unexpected failure") })
        val request = request()
        assertTrue(owner.submit(request))
        assertTrue(owner.running)
        assertFalse(owner.submit(request.copy(id = request.id.copy(operation = 2))))
        runCurrent()
        assertEquals(0, commits)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, commits)
        assertEquals(GlobalIconApplyState.Finished(request.id, true), owner.state.value)
    }

    @Test fun resultIsRetainedWithoutCollectorsAndOnlyItsExactOwnerCanConsumeIt() = runTest {
        val owner = GlobalIconApplyOwner(backgroundScope, { _, acknowledge ->
            acknowledge(); IconApplyOutcome.APPLIED
        }, { _, _ -> fail("Unexpected failure") })
        val request = request()
        owner.submit(request)
        runCurrent()
        assertNull(owner.consume(request.id.copy(editorId = 2)))
        assertNull(owner.consume(request.id.copy(operation = 2)))
        assertNull(owner.consume(request.id.copy(targetKey = "other/Main")))
        assertNull(owner.consume(request.id.copy(session = request.id.session.copy(revision = 2))))
        assertNull(owner.consume(request.id.copy(draftRevision = 3)))
        assertFalse(owner.submit(request))
        assertTrue(requireNotNull(owner.consume(request.id)).applied)
        assertNull(owner.consume(request.id))
        assertEquals(GlobalIconApplyState.Idle, owner.state.value)
    }

    @Test fun thrownFailureCanBeConsumedAndRetriedWithoutClaimingSuccess() = runTest {
        var failures = 0
        var fail = true
        val owner = GlobalIconApplyOwner(backgroundScope, { _, acknowledge ->
            if (fail) throw IllegalStateException("Commit failed")
            acknowledge(); IconApplyOutcome.APPLIED
        }, { outcome, error -> assertEquals(IconApplyOutcome.FAILED, outcome); assertNotNull(error); failures++ })
        val request = request()
        owner.submit(request)
        runCurrent()
        assertFalse(requireNotNull(owner.consume(request.id)).applied)
        assertEquals(1, failures)
        fail = false
        val retry = request.copy(id = request.id.copy(operation = 2))
        assertTrue(owner.submit(retry))
        runCurrent()
        assertTrue(requireNotNull(owner.consume(retry.id)).applied)
    }

    @Test fun rejectedProviderOutcomeRemainsUnapplied() = runTest {
        var outcome: IconApplyOutcome? = null
        val owner = GlobalIconApplyOwner(backgroundScope, { _, _ -> IconApplyOutcome.LOCKED }, { value, _ -> outcome = value })
        val request = request()
        owner.submit(request)
        runCurrent()
        assertEquals(IconApplyOutcome.LOCKED, outcome)
        assertFalse(requireNotNull(owner.consume(request.id)).applied)
    }

    @Test fun cancellationAfterAcknowledgementStillPublishesApplied() = runTest {
        val owner = GlobalIconApplyOwner(backgroundScope, { _, acknowledge ->
            acknowledge(); throw CancellationException("Cancelled after commit")
        }, { _, _ -> fail("A committed icon must not be reported as failed") })
        val request = request()
        owner.submit(request)
        runCurrent()
        assertTrue(requireNotNull(owner.consume(request.id)).applied)
    }

    @Test fun cancellationBeforeCommitNeverPublishesApplied() = runTest {
        val owner = GlobalIconApplyOwner(backgroundScope, { _, _ -> awaitCancellation() }, { _, _ -> fail("Cancellation is not a write failure") })
        val request = request()
        owner.submit(request)
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertFalse(requireNotNull(owner.consume(request.id)).applied)
    }

    @Test fun cancellationBeforeTheJobStartsReleasesItsClaim() = runTest {
        val owner = GlobalIconApplyOwner(backgroundScope, { _, _ -> fail("Commit must not start"); IconApplyOutcome.FAILED }, { _, _ -> })
        val request = request()
        owner.submit(request)
        backgroundScope.cancel()
        runCurrent()
        assertFalse(requireNotNull(owner.consume(request.id)).applied)
        assertFalse(owner.submit(request.copy(id = request.id.copy(operation = 2))))
    }
}
