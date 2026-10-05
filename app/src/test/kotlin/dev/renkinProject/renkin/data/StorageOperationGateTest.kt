package dev.renkinProject.renkin.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class StorageOperationGateTest {
    @Before fun reset() = StorageOperationGate.resetForTests()
    @After fun cleanup() = StorageOperationGate.resetForTests()

    @Test fun startupBarrierRecoversBeforeAnyWriterAndRunsOnlyOnce() = runBlocking {
        val events = mutableListOf<String>()
        StorageOperationGate.installRecovery {
            StorageOperationGate.run { events += "restore" }
        }
        StorageOperationGate.run { events += "write" }
        StorageOperationGate.run { events += "next" }
        assertEquals(listOf("restore", "write", "next"), events)
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }

    @Test fun nestedOwnershipIsReentrantButOtherCoroutinesWait() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            StorageOperationGate.run {
                StorageOperationGate.runWithBoundedWait { entered.complete(Unit) }
                release.await()
            }
        }
        entered.await()
        var wrote = false
        val waiting = async { StorageOperationGate.run { wrote = true } }
        yield()
        assertFalse(wrote)
        release.complete(Unit)
        owner.join()
        waiting.await()
        assertTrue(wrote)
    }

    @Test fun boundedAcquisitionDoesNotQueueRejectedWrite() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            StorageOperationGate.run { entered.complete(Unit); release.await() }
        }
        entered.await()
        var wrote = false
        val error = runCatching { StorageOperationGate.runWithBoundedWait { wrote = true } }.exceptionOrNull()
        assertTrue(error is StorageOperationBusyException)
        release.complete(Unit)
        owner.join()
        StorageOperationGate.run { }
        assertFalse(wrote)
    }

    @Test fun failureBlocksWritersAndRetryKeepsBarrierUntilSessionReload() = runBlocking {
        var recoveryFails = true
        StorageOperationGate.installRecovery { if (recoveryFails) throw StorageRecoverySchemaException("schema") }
        assertTrue(runCatching { StorageOperationGate.run { fail("must not write") } }.exceptionOrNull() is StorageRecoverySchemaException)
        assertEquals(StorageRecoveryFailureReason.SCHEMA_CHANGED, StorageOperationGate.recoveryFailureReason.value)
        assertTrue(runCatching { StorageOperationGate.run { } }.exceptionOrNull() is StorageRecoveryUnavailableException)
        recoveryFails = false
        StorageOperationGate.retryRecovery {
            assertEquals(StorageRecoveryState.CHECKING, StorageOperationGate.recoveryState.value)
            StorageOperationGate.run { }
        }
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
        assertNull(StorageOperationGate.recoveryFailureReason.value)
    }

    @Test fun failedAbandonPreservesOriginalReasonAndSuccessfulAbandonDoesNotRetrySnapshot() = runBlocking {
        var attempts = 0
        StorageOperationGate.installRecovery { attempts++; throw StorageRecoverySnapshotException("checksum") }
        runCatching { StorageOperationGate.run { } }
        assertTrue(runCatching { StorageOperationGate.abandonRecovery { throw IOException("rename") } }.isFailure)
        assertEquals(StorageRecoveryState.FAILED, StorageOperationGate.recoveryState.value)
        assertEquals(StorageRecoveryFailureReason.CORRUPT_SNAPSHOT, StorageOperationGate.recoveryFailureReason.value)
        StorageOperationGate.abandonRecovery { StorageOperationGate.run { } }
        assertEquals(1, attempts)
        StorageOperationGate.run { }
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }

    @Test fun suppressedRollbackCauseDeterminesFailureReason() = runBlocking {
        val failure = IOException("import failed").apply {
            addSuppressed(StorageRecoverySchemaException("rollback schema"))
        }
        StorageOperationGate.installRecovery { throw failure }
        runCatching { StorageOperationGate.run { } }
        assertEquals(StorageRecoveryFailureReason.SCHEMA_CHANGED, StorageOperationGate.recoveryFailureReason.value)
    }

    @Test fun observePausesExistingSubscriptionAndResumesWithLatestValue() = runBlocking {
        val source = MutableStateFlow(1)
        val values = Channel<Int>(Channel.UNLIMITED)
        val subscription = launch { StorageOperationGate.observe(source).collect { values.send(it) } }
        try {
            assertEquals(1, withTimeout(2000) { values.receive() })
            StorageOperationGate.requireRecovery(StorageRecoverySnapshotException("missing"))
            yield()
            source.value = 2
            yield()
            assertTrue(values.tryReceive().isFailure)
            StorageOperationGate.abandonRecovery { }
            assertEquals(2, withTimeout(2000) { values.receive() })
        } finally { subscription.cancelAndJoin(); values.close() }
    }

    @Test fun cancelledWaiterDoesNotKeepTheLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { StorageOperationGate.run { entered.complete(Unit); release.await() } }
        entered.await()
        val waiter = launch { StorageOperationGate.run { fail("cancelled waiter wrote") } }
        yield()
        waiter.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        assertEquals("next", withTimeout(2000) { StorageOperationGate.run { "next" } })
    }
}
