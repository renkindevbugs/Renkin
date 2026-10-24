package dev.renkinProject.renkin.feature.watch

import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.watch.WatchApplySnapshot
import dev.renkinProject.renkin.data.watch.IconSuggestionCandidate
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class WatchApplyRequest(
    val id: Long,
    val session: ProfileSessionToken,
    val snapshot: WatchApplySnapshot,
    val candidate: IconSuggestionCandidate,
    val app: PackageInfoStruct,
    val icon: IconPackDrawable
)

internal enum class WatchApplyPhase { APPLYING, CLEANING, NOT_APPLIED, APPLIED_CLEANED, APPLIED_CLEANUP_FAILED }

internal data class WatchApplyState(
    val request: WatchApplyRequest,
    val phase: WatchApplyPhase,
    val outcome: IconApplyOutcome = IconApplyOutcome.FAILED,
    val cleanupAttempt: Long = 0
) {
    val busy: Boolean get() = phase == WatchApplyPhase.APPLYING || phase == WatchApplyPhase.CLEANING
}

internal class WatchApplyOwner(
    private val scope: CoroutineScope,
    private val apply: suspend (request: WatchApplyRequest, onCommitted: () -> Unit) -> IconApplyOutcome,
    private val cleanup: suspend (WatchApplyRequest) -> Unit,
    private val onResult: (WatchApplyState, Exception?) -> Unit
) {
    private val mutableStates = MutableStateFlow<Map<Long, WatchApplyState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    private fun replace(expected: WatchApplyState?, next: WatchApplyState?, suggestionId: Long): Boolean {
        while (true) {
            val old = states.value
            if (old[suggestionId] != expected) return false
            val updated = if (next == null) old - suggestionId else old + (suggestionId to next)
            if (mutableStates.compareAndSet(old, updated)) return true
        }
    }

    fun submit(request: WatchApplyRequest): Boolean {
        if (!scope.isActive) return false
        val key = request.snapshot.suggestion.id
        val previous = states.value[key]
        if (previous != null && (previous.busy || previous.request.snapshot.storageEpoch == request.snapshot.storageEpoch)) return false
        val running = WatchApplyState(request, WatchApplyPhase.APPLYING)
        if (!replace(previous, running, key)) return false
        runRequest(running, applyIcon = true)
        return true
    }

    fun retryCleanup(requestId: Long, suggestionId: Long): Boolean {
        if (!scope.isActive) return false
        val previous = states.value[suggestionId] ?: return false
        if (previous.request.id != requestId || previous.phase != WatchApplyPhase.APPLIED_CLEANUP_FAILED) return false
        val running = previous.copy(phase = WatchApplyPhase.CLEANING, cleanupAttempt = previous.cleanupAttempt + 1)
        if (!replace(previous, running, suggestionId)) return false
        runRequest(running, applyIcon = false)
        return true
    }

    private fun runRequest(initial: WatchApplyState, applyIcon: Boolean) {
        val request = initial.request
        val key = request.snapshot.suggestion.id
        val committed = AtomicBoolean(!applyIcon)
        fun finish(phase: WatchApplyPhase, outcome: IconApplyOutcome = IconApplyOutcome.FAILED, error: Exception? = null) {
            val current = states.value[key] ?: return
            if (current.request.id != request.id || current.cleanupAttempt != initial.cleanupAttempt || !current.busy) return
            val completed = current.copy(phase = phase, outcome = outcome)
            if (replace(current, completed, key)) onResult(completed, error)
        }
        fun interrupted() = finish(if (committed.get()) WatchApplyPhase.APPLIED_CLEANUP_FAILED else WatchApplyPhase.NOT_APPLIED)
        scope.launch {
            try {
                if (applyIcon) {
                    val outcome = apply(request) {
                        committed.set(true)
                        replace(initial, initial.copy(phase = WatchApplyPhase.CLEANING), key)
                    }
                    if (!committed.get()) {
                        finish(WatchApplyPhase.NOT_APPLIED, outcome)
                        return@launch
                    }
                }
                cleanup(request)
                finish(WatchApplyPhase.APPLIED_CLEANED, IconApplyOutcome.APPLIED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                finish(if (committed.get()) WatchApplyPhase.APPLIED_CLEANUP_FAILED else WatchApplyPhase.NOT_APPLIED,
                    error = error)
            } finally { interrupted() }
        }.invokeOnCompletion { interrupted() }
    }

    fun consume(requestId: Long, suggestionId: Long): Boolean {
        val current = states.value[suggestionId] ?: return false
        if (current.request.id != requestId || current.busy || current.phase == WatchApplyPhase.APPLIED_CLEANUP_FAILED) return false
        return replace(current, null, suggestionId)
    }
}
