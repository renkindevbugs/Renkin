package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackChangesFlowTest {
    private val token = ProfileSessionToken(1, 1, "provider")
    private val app = PackageInfoStruct("App", "com.app", "Main", ColorDrawable(), 0)
    private suspend fun <T> awaitBackground(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    @Test
    fun sharingStopsAfterTimeoutAndResumesFromLatestInputsOffTheCollectorThread() = runTest {
        val apps = MutableStateFlow(ApplicationListState(
            revision = 1, applications = persistentListOf(app), sessionToken = token
        ))
        val baseline = MutableStateFlow(PackChangeBaselines(token, mapOf(app.key to "built"), emptyMap(), emptySet()))
        val subscriptions = AtomicInteger()
        val started = Channel<String>(Channel.UNLIMITED)
        val stopped = Channel<Unit>(Channel.UNLIMITED)
        val tracked = flow {
            subscriptions.incrementAndGet()
            started.trySend(Thread.currentThread().name)
            try { emitAll(baseline) } finally {
                subscriptions.decrementAndGet()
                stopped.trySend(Unit)
            }
        }
        val states = observePackChanges(apps, tracked, backgroundScope)
        runCurrent()
        assertEquals(0, subscriptions.get())
        val subscriber = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.collect { } }
        runCurrent()
        assertTrue(awaitBackground { started.receive() }.contains("DefaultDispatcher"))
        val first = awaitBackground { states.first { it.applicationRevision == 1L } }
        assertEquals(PackChangeKind.REMOVED, first.changes.single().kind)
        val disconnectedAt = System.nanoTime()
        subscriber.cancel()
        runCurrent()
        // flowOn's fused sharing context uses Default, including the subscription timeout.
        withContext(Dispatchers.Default) {
            delay(100)
            assertEquals(1, subscriptions.get())
            withTimeout(8_000) { stopped.receive() }
        }
        assertTrue((System.nanoTime() - disconnectedAt) / 1_000_000 >= 4_500)
        assertEquals(0, subscriptions.get())
        apps.value = apps.value.copy(revision = 2)
        baseline.value = baseline.value.copy(built = emptyMap())
        runCurrent()
        assertEquals(1L, states.value.applicationRevision)
        val resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.collect { } }
        runCurrent()
        awaitBackground { started.receive() }
        val latest = awaitBackground { states.first { it.applicationRevision == 2L } }
        assertTrue(latest.changes.isEmpty())
        assertEquals(1, subscriptions.get())
        resumed.cancel()
    }

    @Test
    fun changedSessionRejectsOldBaselinesAndDelayedDiffsCannotMatchNewLists() = runTest {
        val original = ApplicationListState(1, persistentListOf(app), sessionToken = token)
        val apps = MutableStateFlow(original)
        val baseline = MutableStateFlow(PackChangeBaselines(token, mapOf(app.key to "built"), emptyMap(), emptySet()))
        val states = observePackChanges(apps, baseline, backgroundScope)
        val subscriber = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.collect { } }
        runCurrent()
        val previous = awaitBackground { states.first { it.applicationRevision == 1L } }
        assertFalse(previous.changesFor(original).isEmpty())
        val newer = original.copy(revision = 2, sessionToken = token.copy(profileId = 2))
        apps.value = newer
        assertTrue(previous.changesFor(newer).isEmpty())
        assertTrue(previous.changesFor(original.copy(revision = 3)).isEmpty())
        awaitBackground { states.first { it.sessionToken == null } }
        baseline.value = baseline.value.copy(owner = newer.sessionToken)
        val current = awaitBackground { states.first { it.applicationRevision == 2L } }
        assertEquals(PackChangeKind.REMOVED, current.changesFor(newer).single().kind)
        subscriber.cancel()
    }
}
