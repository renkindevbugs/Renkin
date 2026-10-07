package dev.renkinProject.renkin.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.io.IOException

class StorageOperationBusyException : IllegalStateException("Storage is busy")
class StorageRecoveryUnavailableException : IOException("Storage recovery has not finished")
class StorageRecoverySchemaException(message: String) : IOException(message)
class StorageRecoverySnapshotException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class StorageRecoveryState { READY, CHECKING, RECOVERING, FAILED }
enum class StorageRecoveryFailureReason { SCHEMA_CHANGED, CORRUPT_SNAPSHOT, IO, UNKNOWN }

object StorageOperationGate {
    private val mutex = Mutex()
    private val mutableRecoveryState = MutableStateFlow(StorageRecoveryState.READY)
    private val mutableObserversPaused = MutableStateFlow(false)
    val recoveryState = mutableRecoveryState.asStateFlow()
    private val mutableFailureReason = MutableStateFlow<StorageRecoveryFailureReason?>(null)
    val recoveryFailureReason = mutableFailureReason.asStateFlow()
    private var recovery: (suspend () -> Unit)? = null
    @Volatile private var recoveryRequired = false
    @Volatile var epoch: Long = 0L
        private set

    suspend fun markStorageReplaced() {
        check(currentCoroutineContext()[Owner]?.active == true)
        epoch++
        // Suspend observers until restore/bootstrap and the outer storage operation finish.
        mutableObserversPaused.value = true
    }

    internal fun resetForTests() {
        check(!mutex.isLocked)
        recovery = null
        ProfileStyleAccess.resetForTests()
        recoveryRequired = false
        mutableFailureReason.value = null
        epoch = 0L
        mutableRecoveryState.value = StorageRecoveryState.READY
        mutableObserversPaused.value = false
    }

    private class Owner : AbstractCoroutineContextElement(Key) {
        @Volatile var active = true
        companion object Key : CoroutineContext.Key<Owner>
    }

    fun installRecovery(operation: suspend () -> Unit) {
        check(recovery == null)
        recovery = operation
        recoveryRequired = true
        mutableFailureReason.value = null
        mutableRecoveryState.value = StorageRecoveryState.CHECKING
        mutableObserversPaused.value = true
    }

    fun reportRecoveryRunning() {
        check(recoveryRequired)
        mutableRecoveryState.value = StorageRecoveryState.RECOVERING
    }

    fun requireRecovery(error: Throwable? = null) {
        recoveryRequired = true
        mutableObserversPaused.value = true
        if (error != null) mutableFailureReason.value = failureReason(error)
        else if (mutableFailureReason.value == null) mutableFailureReason.value = StorageRecoveryFailureReason.UNKNOWN
        mutableRecoveryState.value = StorageRecoveryState.FAILED
    }

    private fun failureReason(error: Throwable): StorageRecoveryFailureReason {
        val errors = mutableSetOf<Throwable>()
        fun collect(current: Throwable) {
            if (!errors.add(current)) return
            current.cause?.let(::collect)
            current.suppressed.forEach(::collect)
        }
        collect(error)
        return when {
            errors.any { it is StorageRecoverySchemaException } -> StorageRecoveryFailureReason.SCHEMA_CHANGED
            errors.any { it is StorageRecoverySnapshotException } -> StorageRecoveryFailureReason.CORRUPT_SNAPSHOT
            errors.any { it is IOException || it is android.system.ErrnoException } -> StorageRecoveryFailureReason.IO
            else -> StorageRecoveryFailureReason.UNKNOWN
        }
    }

    suspend fun <T> run(operation: suspend () -> T): T = acquire(null, operation)

    suspend fun <T> runWithBoundedWait(operation: suspend () -> T): T = acquire(300L, operation)

    suspend fun <T> retryRecovery(operation: suspend () -> T): T = acquire(null, operation, allowRecoveryRetry = true)

    suspend fun <T> abandonRecovery(operation: suspend () -> T): T =
        acquire(null, operation, abandonRecovery = true)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(source: Flow<T>): Flow<T> = mutableObserversPaused.flatMapLatest { paused ->
        if (paused) emptyFlow() else source
    }.buffer(0)

    private suspend fun <T> acquire(timeoutMillis: Long?, operation: suspend () -> T, allowRecoveryRetry: Boolean = false, abandonRecovery: Boolean = false): T {
        // The outer operation owns the complete snapshot/commit. Nested repository and
        // preference calls inherit its ownership without acquiring the mutex a second time.
        if (currentCoroutineContext()[Owner]?.active == true) {
            if (recoveryRequired && mutableRecoveryState.value == StorageRecoveryState.FAILED) throw StorageRecoveryUnavailableException()
            return operation()
        }
        var acquired = false
        try {
            if (timeoutMillis == null) {
                mutex.lock()
                acquired = true
            } else {
                val available = withTimeoutOrNull(timeoutMillis) {
                    mutex.lock()
                    acquired = true
                    true
                } == true
                if (!available) throw StorageOperationBusyException()
            }
            val owner = Owner()
            try {
                return withContext(owner) {
                    currentCoroutineContext().ensureActive()
                    val recovering = recoveryRequired
                    if (abandonRecovery) check(recovering && mutableRecoveryState.value == StorageRecoveryState.FAILED)
                    if (recoveryRequired) {
                        if (mutableRecoveryState.value == StorageRecoveryState.FAILED && !allowRecoveryRetry && !abandonRecovery) {
                            throw StorageRecoveryUnavailableException()
                        }
                        mutableRecoveryState.value = StorageRecoveryState.CHECKING
                        try {
                            if (!abandonRecovery) {
                                checkNotNull(recovery) { "Storage recovery is unavailable" }.invoke()
                                recoveryRequired = false
                            }
                        } catch (error: Throwable) {
                            requireRecovery(error)
                            throw error
                        }
                    }
                    try {
                        operation().also { if (abandonRecovery) recoveryRequired = false }
                    } catch (error: Throwable) {
                        if (recovering && (allowRecoveryRetry || abandonRecovery)) {
                            requireRecovery(if (abandonRecovery) null else error)
                        }
                        throw error
                    } finally {
                        if (recovering && !recoveryRequired) {
                            mutableFailureReason.value = null
                            mutableRecoveryState.value = StorageRecoveryState.READY
                        }
                        if (!recoveryRequired) mutableObserversPaused.value = false
                    }
                }
            } finally { owner.active = false }
        } finally {
            if (acquired) mutex.unlock()
        }
    }
}
