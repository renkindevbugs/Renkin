package dev.renkinProject.renkin

import android.content.Context
import androidx.annotation.MainThread
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.editor.EditorCaptureResult
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import dev.renkinProject.renkin.data.editor.EditorDraftInspection
import dev.renkinProject.renkin.data.editor.EditorDraftOwner
import dev.renkinProject.renkin.data.editor.EditorDraftResetLease
import dev.renkinProject.renkin.data.editor.EditorDraftResources
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal enum class EditorAutosaveStatus { WAITING, SAVING, SAVED, BLOCKED, FAILED }
internal enum class EditorAutosaveAttempt { FINISHED, RETRY }

internal data class EditorAutosaveState(
    val lifetime: EditorSessionLifetime,
    val status: EditorAutosaveStatus,
    val checkpoint: EditorDraftCheckpoint? = null,
    val resetLease: EditorDraftResetLease? = null,
    val draftApplicationName: String? = null
)

/** Main-owned bookkeeping; every suspend operation runs in the recovery owner's serial queue. */
@MainThread
internal class EditorDraftAutosave(
    context: Context,
    private val provider: ApplicationProvider,
    private val store: EditorDraftStore
) {
    private class Live(val lifetime: EditorSessionLifetime) {
        var checkpoint: EditorDraftCheckpoint? = null
        var writable = false
        var admitted = false
        var ownsCheckpoint = false
        var savedFrame: EditorAutosaveFrame? = null
        var unsupportedCaptureFailed = false
        var draftApplicationName: String? = null
        var attemptId: String? = null
        var attemptFrame: EditorAutosaveFrame? = null
    }
    private val resources = EditorDraftResources(context)
    private val inputs = IconModifierInputStore(context)
    private val mutableState = MutableStateFlow<EditorAutosaveState?>(null)
    val state = mutableState.asStateFlow()
    private var live: Live? = null
    private val closing = mutableSetOf<EditorSessionLifetime>()

    fun markClosing(lifetime: EditorSessionLifetime) { closing += lifetime }

    fun frameChanged(frame: EditorAutosaveFrame?) {
        val current = live?.takeIf { it.lifetime == frame?.lifetime } ?: return
        val status = mutableState.value?.status
        if (status == EditorAutosaveStatus.SAVED && (frame == null || !frame.ready || !frame.sameContentAs(current.savedFrame))) {
            publish(current, EditorAutosaveStatus.WAITING)
        }
    }

    fun unavailable(editor: EditorSessionViewModel) {
        val lifetime = editor.lifetime.value
        if (lifetime.sessionId == null) return
        val current = live?.takeIf { it.lifetime == lifetime } ?: Live(lifetime).also { live = it }
        current.writable = false
        publish(current, EditorAutosaveStatus.FAILED)
    }

    fun captureStalled(frame: EditorAutosaveFrame) {
        live?.takeIf { it.lifetime == frame.lifetime && it.lifetime !in closing }
            ?.let { publish(it, EditorAutosaveStatus.FAILED) }
    }

    fun attachRecovered(editor: EditorSessionViewModel, checkpoint: EditorDraftCheckpoint, owner: EditorDraftOwner) {
        val lifetime = editor.lifetime.value
        if (live?.lifetime == lifetime) return
        live = Live(lifetime).apply {
            this.checkpoint = checkpoint
            writable = true
            admitted = true
            ownsCheckpoint = true
            savedFrame = editor.initialAutosaveFrame()
            draftApplicationName = applicationName(owner)
        }
        publish(requireNotNull(live), EditorAutosaveStatus.SAVED)
    }

    suspend fun observe(editor: EditorSessionViewModel) {
        val lifetime = editor.lifetime.value
        if (live?.lifetime?.let { it in closing } == true) return
        closing.removeAll { it.generation < lifetime.generation && it != live?.lifetime }
        if (live?.lifetime == lifetime) {
            if (mutableState.value?.status == EditorAutosaveStatus.BLOCKED && provider.runtimeState.value.ready &&
                StorageOperationGate.recoveryState.value == StorageRecoveryState.READY) {
                inspect(requireNotNull(live), allowEmpty = true)
            }
            return
        }
        live = null
        mutableState.value = null
        if (lifetime.sessionId == null || lifetime in closing) return
        val current = Live(lifetime)
        live = current
        publish(current, EditorAutosaveStatus.WAITING)
        inspect(current, allowEmpty = true)
    }

    suspend fun save(
        editor: EditorSessionViewModel, frame: EditorAutosaveFrame, force: Boolean = false
    ): EditorAutosaveAttempt {
        if (editor.lifetime.value != frame.lifetime || frame.lifetime in closing) return EditorAutosaveAttempt.FINISHED
        observe(editor)
        val current = live?.takeIf { it.lifetime == frame.lifetime } ?: return EditorAutosaveAttempt.FINISHED
        if (!current.writable || !available(editor, frame)) return EditorAutosaveAttempt.FINISHED
        // Reverting an already saved edit must still replace that checkpoint with the opening state.
        if (frame.unchangedFromOpening && (current.savedFrame == null || current.savedFrame?.unchangedFromOpening == true)) {
            current.unsupportedCaptureFailed = false
            publish(current, if (current.ownsCheckpoint) EditorAutosaveStatus.SAVED else EditorAutosaveStatus.WAITING)
            return EditorAutosaveAttempt.FINISHED
        }
        if (!force && frame.sameContentAs(current.savedFrame)) {
            publish(current, EditorAutosaveStatus.SAVED)
            return EditorAutosaveAttempt.FINISHED
        }
        if (frame.witness == null) {
            if (force || !current.unsupportedCaptureFailed) {
                current.unsupportedCaptureFailed = true
                publish(current, EditorAutosaveStatus.FAILED)
            }
            return EditorAutosaveAttempt.FINISHED
        }
        current.unsupportedCaptureFailed = false
        val expected = current.checkpoint ?: return EditorAutosaveAttempt.FINISHED
        publish(current, EditorAutosaveStatus.SAVING)
        try {
            val captured = withContext(Dispatchers.Default) {
                editor.captureCompleteDraft(requireNotNull(frame.lifetime.sessionId), expected, provider, store, resources, inputs)
            }
            when (captured) {
                EditorCaptureResult.Superseded -> {
                    inspect(current)
                    frameChanged(editor.autosaveFrame())
                    return if (current.writable && available(editor, frame)) EditorAutosaveAttempt.RETRY
                        else EditorAutosaveAttempt.FINISHED
                }
                is EditorCaptureResult.Failed -> {
                    publish(current, EditorAutosaveStatus.FAILED)
                    return EditorAutosaveAttempt.FINISHED
                }
                is EditorCaptureResult.Ready -> {
                    if (!available(editor, frame)) {
                        publish(current, EditorAutosaveStatus.WAITING)
                        return EditorAutosaveAttempt.FINISHED
                    }
                    val valid = withContext(Dispatchers.Default) { provider.validateEditorDraftBaseline(captured.draft.baseline) }
                    if (!valid || !available(editor, frame)) {
                        publish(current, EditorAutosaveStatus.WAITING)
                        return if (!valid && available(editor, frame)) EditorAutosaveAttempt.RETRY
                            else EditorAutosaveAttempt.FINISHED
                    }
                    // Identifies publication even when final directory fsync fails after AtomicFile rename.
                    val attempt = UUID.randomUUID().toString()
                    current.attemptId = attempt
                    current.attemptFrame = frame
                    val saved = captured.draft.save(store, attempt)
                    current.checkpoint = saved.checkpoint
                    current.ownsCheckpoint = true
                    current.savedFrame = frame
                    current.draftApplicationName = applicationName(captured.draft.owner)
                    current.attemptId = null
                    current.attemptFrame = null
                    publish(current, if (available(editor, frame)) EditorAutosaveStatus.SAVED else EditorAutosaveStatus.WAITING)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failed(current, error) }
        catch (error: OutOfMemoryError) { failed(current, error) }
        return EditorAutosaveAttempt.FINISHED
    }

    suspend fun retry(editor: EditorSessionViewModel, shown: EditorAutosaveState) {
        if (mutableState.value !== shown || editor.lifetime.value != shown.lifetime) return
        val current = live ?: return
        inspect(current, allowEmpty = current.checkpoint == null)
        editor.autosaveFrame()?.let { save(editor, it, force = true) }
    }

    suspend fun discard(editor: EditorSessionViewModel, shown: EditorAutosaveState) {
        if (mutableState.value !== shown || editor.lifetime.value != shown.lifetime) return
        val current = live ?: return
        publish(current, EditorAutosaveStatus.SAVING)
        try {
            val cleared = if (shown.resetLease != null) store.discardUnreadable(shown.resetLease)
            else shown.checkpoint?.let { if (store.discard(it)) store.checkpoint() else null }
            if (cleared == null || cleared.draftId != null) {
                inspect(current)
                return
            }
            current.checkpoint = cleared
            current.writable = true
            current.admitted = true
            current.ownsCheckpoint = false
            current.savedFrame = null
            current.draftApplicationName = null
            current.attemptId = null
            current.attemptFrame = null
            publish(current, EditorAutosaveStatus.WAITING)
            editor.autosaveFrame()?.let { save(editor, it, force = true) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failed(current, error) }
    }

    /** Session loss preserves files; only this explicit-close command hands a checkpoint to cleanup. */
    fun close(lifetime: EditorSessionLifetime): EditorDraftCheckpoint? {
        val current = live?.takeIf { it.lifetime == lifetime } ?: return null
        live = null
        mutableState.value = null
        closing.remove(lifetime)
        return current.checkpoint.takeIf { current.ownsCheckpoint }
    }

    private fun available(editor: EditorSessionViewModel, frame: EditorAutosaveFrame): Boolean =
        frame.lifetime !in closing && frame.storageEpoch == StorageOperationGate.epoch &&
            StorageOperationGate.recoveryState.value == StorageRecoveryState.READY &&
            provider.isCurrentSession(frame.owner) && editor.ownsAutosaveFrame(frame)

    private suspend fun failed(current: Live, error: Throwable) {
        Log.error("EditorDraftAutosave", "Editor recovery checkpoint could not be saved", error)
        // Reconcile exact publication ownership before permitting Retry or any cleanup.
        try { inspect(current, reportFailure = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (inspectionError: Exception) {
            Log.error("EditorDraftAutosave", "Editor checkpoint inspection failed", inspectionError)
            current.writable = false
            publish(current, EditorAutosaveStatus.FAILED)
        }
    }

    private suspend fun inspect(current: Live, allowEmpty: Boolean = false, reportFailure: Boolean = false) {
        when (val inspected = store.inspectCheckpoint(includeOwner = true)) {
            is EditorDraftInspection.Unreadable -> {
                current.writable = false
                mutableState.value = EditorAutosaveState(current.lifetime, EditorAutosaveStatus.FAILED,
                    resetLease = inspected.resetLease)
            }
            is EditorDraftInspection.Readable -> {
                val checkpoint = inspected.checkpoint
                current.draftApplicationName = inspected.owner?.let(::applicationName)
                val publishedAttempt = current.attemptId != null &&
                    checkpoint.storageGeneration == current.checkpoint?.storageGeneration &&
                    checkpoint.draftId == current.attemptId
                if (publishedAttempt) {
                    current.checkpoint = checkpoint
                    current.ownsCheckpoint = true
                    current.writable = true
                    current.admitted = true
                    current.savedFrame = current.attemptFrame
                    current.attemptId = null
                    current.attemptFrame = null
                    publish(current, if (reportFailure) EditorAutosaveStatus.FAILED else EditorAutosaveStatus.SAVED)
                } else if (checkpoint == current.checkpoint && current.admitted) {
                    current.writable = true
                    publish(current, if (reportFailure) EditorAutosaveStatus.FAILED
                        else if (current.savedFrame != null) EditorAutosaveStatus.SAVED else EditorAutosaveStatus.WAITING)
                } else {
                    current.checkpoint = checkpoint
                    current.ownsCheckpoint = false
                    current.savedFrame = null
                    current.writable = allowEmpty && checkpoint.draftId == null
                    current.admitted = current.writable
                    current.attemptId = null
                    current.attemptFrame = null
                    publish(current, if (current.writable) EditorAutosaveStatus.WAITING else EditorAutosaveStatus.BLOCKED)
                }
            }
        }
    }

    private fun publish(current: Live, status: EditorAutosaveStatus) {
        if (live === current) mutableState.value = EditorAutosaveState(current.lifetime, status, current.checkpoint,
            draftApplicationName = current.draftApplicationName)
    }

    private fun applicationName(owner: EditorDraftOwner): String =
        provider.applicationState.value.applications.firstOrNull {
            it.packageName == owner.packageName && it.activityName == owner.activityName
        }?.appName?.takeIf { it.isNotBlank() } ?: owner.packageName
}
