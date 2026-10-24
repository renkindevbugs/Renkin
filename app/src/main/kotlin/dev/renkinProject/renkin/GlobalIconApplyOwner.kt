package dev.renkinProject.renkin

import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class GlobalIconApplyId(
    val editorId: Long,
    val operation: Long,
    val session: ProfileSessionToken,
    val targetKey: String,
    val draftRevision: Long
)

internal data class GlobalIconApplyRequest(
    val id: GlobalIconApplyId,
    val app: PackageInfoStruct,
    val options: GenerationOptions,
    val icon: IconPackDrawable,
    val sourcePackName: String?
)

internal sealed interface GlobalIconApplyState {
    data object Idle : GlobalIconApplyState
    data class Running(val request: GlobalIconApplyRequest) : GlobalIconApplyState
    data class Finished(val id: GlobalIconApplyId, val applied: Boolean) : GlobalIconApplyState
}

internal class GlobalIconApplyOwner(
    private val scope: CoroutineScope,
    private val apply: suspend (request: GlobalIconApplyRequest, onCommitted: () -> Unit) -> IconApplyOutcome,
    private val onFailure: (IconApplyOutcome, Exception?) -> Unit
) {
    private val mutableState = MutableStateFlow<GlobalIconApplyState>(GlobalIconApplyState.Idle)
    val state = mutableState.asStateFlow()
    val running: Boolean get() = state.value is GlobalIconApplyState.Running

    fun submit(request: GlobalIconApplyRequest): Boolean {
        require(request.id.targetKey == request.app.key)
        if (!scope.isActive) return false
        val running = GlobalIconApplyState.Running(request)
        // Claim before launching: a second tap cannot enqueue another commit.
        if (!mutableState.compareAndSet(GlobalIconApplyState.Idle, running)) return false
        val committed = AtomicBoolean(false)
        fun finish() {
            mutableState.compareAndSet(running, GlobalIconApplyState.Finished(request.id, committed.get()))
        }
        scope.launch {
            try {
                val outcome = apply(request) { committed.set(true) }
                if (!committed.get()) onFailure(outcome, null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!committed.get()) onFailure(IconApplyOutcome.FAILED, error)
            } finally {
                // withContext can cancel its return after the provider has already committed.
                finish()
            }
        }.invokeOnCompletion { finish() }
        return true
    }

    fun consume(id: GlobalIconApplyId): GlobalIconApplyState.Finished? {
        val result = state.value as? GlobalIconApplyState.Finished ?: return null
        return result.takeIf { it.id == id && mutableState.compareAndSet(it, GlobalIconApplyState.Idle) }
    }
}
