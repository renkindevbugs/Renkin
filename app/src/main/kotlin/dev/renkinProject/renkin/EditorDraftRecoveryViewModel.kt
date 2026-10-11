package dev.renkinProject.renkin

import android.content.Context
import androidx.annotation.MainThread
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
import dev.renkinProject.renkin.util.Log
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface EditorDraftRecoveryState {
    data object Hidden : EditorDraftRecoveryState
    data object Working : EditorDraftRecoveryState
    data class Offer(val checkpoint: EditorDraftCheckpoint, val applicationName: String, val attempt: Long) : EditorDraftRecoveryState
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
        data class Close(val attachment: EditorRecoveryAttachment) : Request
        data class Detach(val attachment: EditorRecoveryAttachment) : Request
    }

    private data class Observation(
        val session: ProfileSessionToken,
        val ready: Boolean,
        val storageReady: Boolean,
        val lifetime: EditorSessionLifetime
    )

    private val resources = EditorRecoveryResources(context)
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
                            is Request.Close -> closeTransferredEditor(request.attachment)
                            is Request.Detach -> {
                                if (accepted?.first == request.attachment) accepted = null
                                requireNotNull(editor).release(request.attachment.sessionId)
                                enqueueObservation()
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        Log.error("EditorDraftRecovery", "Editor draft action failed", error)
                        dropPrepared()
                        mutableState.value = EditorDraftRecoveryState.Failed(null, null, cleanupCheckpoint != null)
                    } catch (error: OutOfMemoryError) {
                        Log.error("EditorDraftRecovery", "Editor draft action exceeded available memory", error)
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
    }

    @MainThread internal fun retry(shown: EditorDraftRecoveryState) { requests.trySend(Request.Retry(shown)) }
    @MainThread internal fun restore(shown: EditorDraftRecoveryState.Offer) { requests.trySend(Request.Restore(shown)) }
    @MainThread internal fun discard(shown: EditorDraftRecoveryState) { requests.trySend(Request.Discard(shown)) }
    @MainThread internal fun postpone(shown: EditorDraftRecoveryState) { requests.trySend(Request.Postpone(shown)) }
    @MainThread internal fun close(attachment: EditorRecoveryAttachment) { requests.trySend(Request.Close(attachment)) }
    @MainThread internal fun detach(attachment: EditorRecoveryAttachment) { requests.trySend(Request.Detach(attachment)) }

    private fun enqueueObservation() {
        if (!observationQueued) observationQueued = requests.trySend(Request.Observe).isSuccess
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
                    try {
                        val recovered = withContext(Dispatchers.Default) {
                            requireNotNull(editor).prepareDraftRecovery(checkpoint, provider, store, resources).also { result = it }
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
                                    recovered.draft.baseline.application.appName, ++attempt)
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

    private suspend fun closeTransferredEditor(attachment: EditorRecoveryAttachment) {
        val attached = accepted?.takeIf { it.first == attachment } ?: return
        accepted = null
        cleanupCheckpoint = attached.second
        requireNotNull(editor).release(attachment.sessionId)
        mutableState.value = EditorDraftRecoveryState.Working
        try {
            store.discard(attached.second)
            cleanupCheckpoint = null
            refresh()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Log.error("EditorDraftRecovery", "Closed editor draft could not be cleared", error)
            showActionFailure(attached.second)
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
        requests.close()
        dropPrepared()
    }
}
