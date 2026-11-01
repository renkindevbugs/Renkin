package dev.renkinProject.renkin.feature.watch

import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.watch.AppComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class WatchRuleSaveRequest(
    val id: Long,
    val session: ProfileSessionToken,
    val profileId: Long,
    val existingRuleId: Long?,
    val apps: List<AppComponent>,
    val watchAllPacks: Boolean,
    val packs: List<String>
)

internal enum class WatchRuleSaveStatus { SAVING, SAVED, NOT_SAVED, SESSION_CHANGED }

internal data class WatchRuleSaveState(val requestId: Long, val status: WatchRuleSaveStatus) {
    val busy: Boolean get() = status == WatchRuleSaveStatus.SAVING
}

/**
 * Keeps the latest rule save's outcome until the opener that issued it acknowledges it, so an
 * outcome finishing while no editor is composed is not lost and never closes another editor.
 * In memory only: process death drops it, while a saved rule is already committed to Room.
 */
internal class WatchRuleSaveOwner(
    private val scope: CoroutineScope,
    private val save: suspend (WatchRuleSaveRequest) -> WatchRuleSaveStatus,
    private val onFinished: (WatchRuleSaveState, WatchRuleSaveRequest, Exception?) -> Unit
) {
    private val mutableState = MutableStateFlow<WatchRuleSaveState?>(null)
    val state = mutableState.asStateFlow()

    fun submit(request: WatchRuleSaveRequest): Boolean {
        if (!scope.isActive) return false
        val previous = state.value
        if (previous?.busy == true) return false
        val saving = WatchRuleSaveState(request.id, WatchRuleSaveStatus.SAVING)
        if (!mutableState.compareAndSet(previous, saving)) return false
        scope.launch {
            var status = WatchRuleSaveStatus.NOT_SAVED
            var failure: Exception? = null
            try {
                status = save(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            } finally {
                val finished = WatchRuleSaveState(request.id, status)
                if (mutableState.compareAndSet(saving, finished)) onFinished(finished, request, failure)
            }
        }
        return true
    }

    fun acknowledge(requestId: Long): Boolean {
        val current = state.value ?: return false
        if (current.requestId != requestId || current.busy) return false
        return mutableState.compareAndSet(current, null)
    }
}

/** One manual check at a time; [check] returns the new suggestion count, or null for a stale run. */
internal class WatchCheckOwner(
    private val scope: CoroutineScope,
    private val check: suspend () -> Int?,
    private val onFinished: (found: Int?, Exception?) -> Unit
) {
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()

    fun start(): Boolean {
        if (!scope.isActive || !mutableRunning.compareAndSet(false, true)) return false
        scope.launch {
            var found: Int? = null
            var failure: Exception? = null
            try {
                found = check()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            } finally {
                mutableRunning.value = false
                onFinished(found, failure)
            }
        }
        return true
    }
}
