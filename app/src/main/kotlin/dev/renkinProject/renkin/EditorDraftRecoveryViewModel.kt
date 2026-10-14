package dev.renkinProject.renkin

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import dev.renkinProject.renkin.data.editor.EditorDraftInspection
import dev.renkinProject.renkin.data.editor.EditorDraftResetLease
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.editor.EditorRecoveryResources
import dev.renkinProject.renkin.ui.EditorRecoveryAcceptance
import dev.renkinProject.renkin.ui.EditorRecoveryAttachment
import dev.renkinProject.renkin.ui.EditorRecoveryPreparation
import dev.renkinProject.renkin.ui.PreparedEditorRecovery
import dev.renkinProject.renkin.ui.EditorRecoveryPreview
import dev.renkinProject.renkin.ui.prepareEditorRecoveryPreview
import dev.renkinProject.renkin.util.Log
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface EditorDraftRecoveryState {
    data object Hidden : EditorDraftRecoveryState
    data object Working : EditorDraftRecoveryState
    data class Offer(val checkpoint: EditorDraftCheckpoint, val applicationName: String, val attempt: Long,
        val profileName: String? = null, val preview: EditorRecoveryPreview? = null) : EditorDraftRecoveryState
    data class NeedsProfile(val checkpoint: EditorDraftCheckpoint, val profileId: Long, val profileName: String) : EditorDraftRecoveryState
    data class Obsolete(val checkpoint: EditorDraftCheckpoint) : EditorDraftRecoveryState
    data class Changed(val checkpoint: EditorDraftCheckpoint) : EditorDraftRecoveryState
    data class Failed(
        val checkpoint: EditorDraftCheckpoint?,
        val resetLease: EditorDraftResetLease?,
        val closingEditor: Boolean = false
    ) : EditorDraftRecoveryState
}

/** Serializes offer actions outside domain gates; only the editor VM may transfer native ownership. */
@HiltViewModel
class EditorDraftRecoveryViewModel @Inject internal constructor(
    @ApplicationContext context: Context,
    private val provider: ApplicationProvider,
    private val store: EditorDraftStore
) : ViewModel() {
    private sealed interface Request {
        data object Observe : Request
        data class Retry(val shown: EditorDraftRecoveryState) : Request
        data class Restore(val shown: EditorDraftRecoveryState.Offer) : Request
        data class Discard(val shown: EditorDraftRecoveryState) : Request
        data class Postpone(val shown: EditorDraftRecoveryState) : Request
        data class Close(val attachment: EditorRecoveryAttachment, val lifetime: EditorSessionLifetime) : Request
        data class Detach(val attachment: EditorRecoveryAttachment) : Request
        data object Save : Request
        data class RetrySave(val shown: EditorAutosaveState) : Request
        data class DiscardForSave(val shown: EditorAutosaveState) : Request
        data class CloseLive(val lifetime: EditorSessionLifetime) : Request
    }

    private data class Observation(
        val session: ProfileSessionToken,
        val ready: Boolean,
        val storageReady: Boolean,
        val lifetime: EditorSessionLifetime
    )

    private val resources = EditorRecoveryResources(context)
    private val autosave = EditorDraftAutosave(context, provider, store)
    internal val autosaveState = autosave.state
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<EditorDraftRecoveryState>(EditorDraftRecoveryState.Hidden)
    internal val state = mutableState.asStateFlow()
    private var editor: EditorSessionViewModel? = null
    private var prepared: PreparedEditorRecovery? = null
    private var accepted: Pair<EditorRecoveryAttachment, EditorDraftCheckpoint>? = null
    private var cleanupCheckpoint: EditorDraftCheckpoint? = null
    private var postponed: Pair<EditorDraftCheckpoint?, Long>? = null
    private var lastObservation: Observation? = null
    private var attempt = 0L
    private var observationQueued = false
    private var saveDelay: Job? = null
    private var pendingSave: EditorAutosaveFrame? = null
    private var saveQueued = false
    private var backgrounded = false
    private var retryFrame: EditorAutosaveFrame? = null
    private var supersededRetries = 0

    @MainThread
    internal fun connect(editorViewModel: EditorSessionViewModel) {
        if (editor === editorViewModel) return
        check(editor == null) { "Recovery owner cannot change editor ViewModels" }
        editor = editorViewModel
        viewModelScope.launch {
            try {
                for (request in requests) {
                    try {
                        when (request) {
                            Request.Observe -> {
                                observationQueued = false
                                val previousLifetime = autosave.state.value?.lifetime
                                autosave.observe(requireNotNull(editor))
                                if (autosave.state.value?.lifetime != previousLifetime) {
                                    scheduleSave(requireNotNull(editor).autosaveFrame())
                                }
                                val observation = observation()
                                if (observation != lastObservation) refresh()
                            }
                            is Request.Retry -> if (mutableState.value === request.shown) {
                                postponed = null
                                val pending = cleanupCheckpoint
                                if (pending == null || checkpointOf(request.shown) == null) refresh()
                                else performDiscard(request.shown)
                            }
                            is Request.Restore -> if (mutableState.value === request.shown) performRestore(request.shown)
                            is Request.Discard -> if (mutableState.value === request.shown) performDiscard(request.shown)
                            is Request.Postpone -> if (mutableState.value === request.shown) {
                                postponed = checkpointOf(request.shown) to provider.runtimeState.value.activeProfileId
                                dropPrepared()
                                mutableState.value = EditorDraftRecoveryState.Hidden
                            }
                            is Request.Close -> closeTransferredEditor(request.attachment, request.lifetime)
                            is Request.Detach -> {
                                if (accepted?.first == request.attachment) accepted = null
                                requireNotNull(editor).release(request.attachment.sessionId)
                                enqueueObservation()
                            }
                            Request.Save -> {
                                saveQueued = false
                                val frame = pendingSave
                                pendingSave = null
                                if (frame != null) {
                                    if (retryFrame != frame) { retryFrame = frame; supersededRetries = 0 }
                                    if (autosave.save(requireNotNull(editor), frame) == EditorAutosaveAttempt.RETRY) {
                                        if (supersededRetries++ < 2) scheduleSave(frame)
                                        else autosave.captureStalled(frame)
                                    }
                                }
                            }
                            is Request.RetrySave -> {
                                autosave.retry(requireNotNull(editor), request.shown)
                                retryPendingSave()
                            }
                            is Request.DiscardForSave -> {
                                autosave.discard(requireNotNull(editor), request.shown)
                                retryPendingSave()
                            }
                            is Request.CloseLive -> {
                                val checkpoint = autosave.close(request.lifetime)
                                if (checkpoint != null) discardClosedCheckpoint(checkpoint) else refresh()
                                enqueueObservation()
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        Log.error("EditorDraftRecovery", "Editor draft action failed", error)
                        autosave.unavailable(requireNotNull(editor))
                        dropPrepared()
                        mutableState.value = EditorDraftRecoveryState.Failed(null, null, cleanupCheckpoint != null)
                    } catch (error: OutOfMemoryError) {
                        Log.error("EditorDraftRecovery", "Editor draft action exceeded available memory", error)
                        autosave.unavailable(requireNotNull(editor))
                        dropPrepared()
                        mutableState.value = EditorDraftRecoveryState.Failed(null, null, cleanupCheckpoint != null)
                    }
                }
            } finally { dropPrepared() }
        }
        viewModelScope.launch {
            combine(provider.runtimeState, StorageOperationGate.recoveryState,
                editorViewModel.lifetime) { _, _, _ -> Unit }
                .collect { enqueueObservation() }
        }
        viewModelScope.launch {
            editorViewModel.lifetime.collectLatest {
                snapshotFlow { editorViewModel.autosaveFrame() }.collect { scheduleSave(it) }
            }
        }
    }

    @MainThread internal fun retry(shown: EditorDraftRecoveryState) { requests.trySend(Request.Retry(shown)) }
    @MainThread internal fun restore(shown: EditorDraftRecoveryState.Offer) { requests.trySend(Request.Restore(shown)) }
    @MainThread internal fun discard(shown: EditorDraftRecoveryState) { requests.trySend(Request.Discard(shown)) }
    @MainThread internal fun postpone(shown: EditorDraftRecoveryState) { requests.trySend(Request.Postpone(shown)) }
    @MainThread internal fun close(attachment: EditorRecoveryAttachment) {
        val owner = requireNotNull(editor)
        if (owner.recoveryAttachment.value != attachment) return
        val lifetime = owner.lifetime.value
        autosave.markClosing(lifetime)
        saveDelay?.cancel()
        owner.release(attachment.sessionId)
        requests.trySend(Request.Close(attachment, lifetime))
    }
    @MainThread internal fun detach(attachment: EditorRecoveryAttachment) { requests.trySend(Request.Detach(attachment)) }
    @MainThread internal fun retryAutosave(shown: EditorAutosaveState) { requests.trySend(Request.RetrySave(shown)) }
    @MainThread internal fun discardForAutosave(shown: EditorAutosaveState) { requests.trySend(Request.DiscardForSave(shown)) }

    @MainThread
    internal fun closeLive(id: String) {
        val owner = requireNotNull(editor)
        val lifetime = owner.lifetime.value.takeIf { it.sessionId == id } ?: return
        autosave.markClosing(lifetime)
        saveDelay?.cancel()
        owner.release(id)
        requests.trySend(Request.CloseLive(lifetime))
    }

    @MainThread
    internal fun setBackgrounded(value: Boolean) {
        backgrounded = value
        if (value) {
            saveDelay?.cancel()
            editor?.autosaveFrame()?.takeIf { it.ready }?.let(::enqueueSave)
        }
    }

    private fun scheduleSave(frame: EditorAutosaveFrame?) {
        saveDelay?.cancel()
        autosave.frameChanged(frame)
        if (frame == null || !frame.ready) return
        saveDelay = viewModelScope.launch {
            if (!backgrounded) delay(600)
            enqueueSave(frame)
        }
    }

    private fun enqueueSave(frame: EditorAutosaveFrame) {
        pendingSave = frame
        if (saveQueued) return
        // Main.immediate can consume the request before trySend returns and clear this flag.
        saveQueued = true
        if (requests.trySend(Request.Save).isFailure) saveQueued = false
    }

    private fun retryPendingSave() {
        if (autosave.state.value?.status != EditorAutosaveStatus.WAITING) return
        retryFrame = null
        supersededRetries = 0
        editor?.autosaveFrame()?.let(::scheduleSave)
    }

    private fun enqueueObservation() {
        if (observationQueued) return
        observationQueued = true
        if (requests.trySend(Request.Observe).isFailure) observationQueued = false
    }

    private fun observation(): Observation {
        val runtime = provider.runtimeState.value
        return Observation(runtime.sessionToken, runtime.ready,
            StorageOperationGate.recoveryState.value == StorageRecoveryState.READY, requireNotNull(editor).lifetime.value)
    }

    private suspend fun refresh() {
        val current = observation()
        lastObservation = current
        dropPrepared()
        if (!current.ready || !current.storageReady || current.lifetime.sessionId != null) {
            mutableState.value = EditorDraftRecoveryState.Hidden
            return
        }
        repeat(3) {
            when (val inspected = store.inspectCheckpoint()) {
                is EditorDraftInspection.Unreadable -> {
                    if (postponed == (null to current.session.profileId)) {
                        mutableState.value = EditorDraftRecoveryState.Hidden
                    } else mutableState.value = EditorDraftRecoveryState.Failed(null, inspected.resetLease, cleanupCheckpoint != null)
                    return
                }
                is EditorDraftInspection.Readable -> {
                    val checkpoint = inspected.checkpoint
                    val pending = cleanupCheckpoint
                    if (pending != null) {
                        if (pending == checkpoint) {
                            mutableState.value = if (postponed == (checkpoint to current.session.profileId)) {
                                EditorDraftRecoveryState.Hidden
                            } else EditorDraftRecoveryState.Failed(checkpoint, null, closingEditor = true)
                            return
                        }
                        cleanupCheckpoint = null
                    }
                    if (checkpoint.draftId == null || postponed == (checkpoint to current.session.profileId)) {
                        mutableState.value = EditorDraftRecoveryState.Hidden
                        return
                    }
                    var result: EditorRecoveryPreparation? = null
                    var preview: EditorRecoveryPreview? = null
                    try {
                        val recovered = withContext(Dispatchers.Default) {
                            requireNotNull(editor).prepareDraftRecovery(checkpoint, provider, store, resources).also {
                                result = it
                                if (it is EditorRecoveryPreparation.Ready) preview = prepareEditorRecoveryPreview(it.draft)
                            }
                        }
                        if (observation() != current) {
                            mutableState.value = EditorDraftRecoveryState.Hidden
                            enqueueObservation()
                            return
                        }
                        when (recovered) {
                            is EditorRecoveryPreparation.Ready -> {
                                prepared = recovered.draft
                                mutableState.value = EditorDraftRecoveryState.Offer(checkpoint,
                                    recovered.draft.baseline.application.appName, ++attempt,
                                    recovered.draft.baseline.profile.name, preview)
                            }
                            is EditorRecoveryPreparation.NeedsProfile -> {
                                val profile = provider.profilesFlow().first().firstOrNull { profile -> profile.id == recovered.profileId }
                                mutableState.value = if (profile != null) {
                                    EditorDraftRecoveryState.NeedsProfile(checkpoint, recovered.profileId, profile.name)
                                } else EditorDraftRecoveryState.Obsolete(checkpoint)
                            }
                            EditorRecoveryPreparation.Obsolete -> mutableState.value = EditorDraftRecoveryState.Obsolete(checkpoint)
                            EditorRecoveryPreparation.Empty -> mutableState.value = EditorDraftRecoveryState.Hidden
                            EditorRecoveryPreparation.Superseded -> {
                                if (it < 2) { delay(50); return@repeat }
                                mutableState.value = EditorDraftRecoveryState.Changed(checkpoint)
                            }
                            is EditorRecoveryPreparation.Failed -> mutableState.value = EditorDraftRecoveryState.Failed(checkpoint, null)
                        }
                        return
                    } finally {
                        val ready = (result as? EditorRecoveryPreparation.Ready)?.draft
                        if (ready !== prepared) ready?.disposeUnattached()
                    }
                }
            }
        }
    }

    private suspend fun performRestore(shown: EditorDraftRecoveryState.Offer) {
        val draft = prepared?.takeIf { it.checkpoint == shown.checkpoint } ?: return refresh()
        mutableState.value = EditorDraftRecoveryState.Working
        val id = UUID.randomUUID().toString()
        try {
            val outcome = withContext(Dispatchers.Default) {
                requireNotNull(editor).acceptDraftRecovery(id, draft, provider, store)
            }
            when (outcome) {
                is EditorRecoveryAcceptance.Accepted -> {
                    accepted = outcome.attachment to draft.checkpoint
                    mutableState.value = EditorDraftRecoveryState.Hidden
                }
                EditorRecoveryAcceptance.Superseded -> refresh()
                is EditorRecoveryAcceptance.Failed -> showActionFailure(draft.checkpoint)
            }
        } finally {
            // Publication can win cancellation or a failed return dispatch. Trust the attachment.
            requireNotNull(editor).recoveryAttachment.value?.takeIf { it.sessionId == id }?.let {
                accepted = it to draft.checkpoint
                autosave.attachRecovered(requireNotNull(editor), draft.checkpoint, draft.owner)
                mutableState.value = EditorDraftRecoveryState.Hidden
            }
            if (prepared === draft) prepared = null
            draft.disposeUnattached()
        }
    }

    private suspend fun performDiscard(shown: EditorDraftRecoveryState) {
        mutableState.value = EditorDraftRecoveryState.Working
        dropPrepared()
        try {
            val failed = shown as? EditorDraftRecoveryState.Failed
            if (failed?.resetLease != null) store.discardUnreadable(failed.resetLease)
            else checkpointOf(shown)?.let { store.discard(it) } ?: return refresh()
            // A null/false result is a stale action, never permission to reset the newer state.
            refresh()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Log.error("EditorDraftRecovery", "Editor draft could not be discarded", error)
            showActionFailure(checkpointOf(shown))
        }
    }

    private suspend fun closeTransferredEditor(attachment: EditorRecoveryAttachment, lifetime: EditorSessionLifetime) {
        val attached = accepted?.takeIf { it.first == attachment } ?: return
        accepted = null
        val checkpoint = autosave.close(lifetime) ?: attached.second
        requireNotNull(editor).release(attachment.sessionId)
        discardClosedCheckpoint(checkpoint)
        enqueueObservation()
    }

    private suspend fun discardClosedCheckpoint(checkpoint: EditorDraftCheckpoint) {
        cleanupCheckpoint = checkpoint
        mutableState.value = EditorDraftRecoveryState.Working
        try {
            store.discard(checkpoint)
            cleanupCheckpoint = null
            refresh()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Log.error("EditorDraftRecovery", "Closed editor draft could not be cleared", error)
            showActionFailure(checkpoint)
        }
    }

    private suspend fun showActionFailure(expected: EditorDraftCheckpoint?) {
        dropPrepared()
        when (val inspected = store.inspectCheckpoint()) {
            is EditorDraftInspection.Unreadable ->
                mutableState.value = EditorDraftRecoveryState.Failed(null, inspected.resetLease, cleanupCheckpoint != null)
            is EditorDraftInspection.Readable -> {
                if (inspected.checkpoint != expected) refresh()
                else mutableState.value = EditorDraftRecoveryState.Failed(expected, null, cleanupCheckpoint != null)
            }
        }
    }

    private fun checkpointOf(shown: EditorDraftRecoveryState): EditorDraftCheckpoint? = when (shown) {
        is EditorDraftRecoveryState.Offer -> shown.checkpoint
        is EditorDraftRecoveryState.NeedsProfile -> shown.checkpoint
        is EditorDraftRecoveryState.Obsolete -> shown.checkpoint
        is EditorDraftRecoveryState.Changed -> shown.checkpoint
        is EditorDraftRecoveryState.Failed -> shown.checkpoint
        else -> null
    }

    private fun dropPrepared() {
        prepared?.disposeUnattached()
        prepared = null
    }

    override fun onCleared() {
        saveDelay?.cancel()
        requests.close()
        dropPrepared()
    }
}
