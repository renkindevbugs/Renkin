package dev.renkinProject.renkin

import android.os.Debug
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileOperationSupersededException
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryUnavailableException
import dev.renkinProject.renkin.data.editor.EditorCaptureResult
import dev.renkinProject.renkin.data.editor.EditorCompleteCodec
import dev.renkinProject.renkin.data.editor.EditorDraftChangedException
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import dev.renkinProject.renkin.data.editor.EditorDraftFingerprints
import dev.renkinProject.renkin.data.editor.EditorDraftOwner
import dev.renkinProject.renkin.data.editor.EditorDraftResources
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.editor.EditorRecoveryResources
import dev.renkinProject.renkin.data.editor.PreparedEditorDraft
import dev.renkinProject.renkin.icon.creator.PreparedModifierReplay
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.BoundEditorRecovery
import dev.renkinProject.renkin.ui.EditorAutosaveContent
import dev.renkinProject.renkin.ui.EditorCapturePendingException
import dev.renkinProject.renkin.ui.EditorCompleteMainCapture
import dev.renkinProject.renkin.ui.EditorRecoveryAcceptance
import dev.renkinProject.renkin.ui.EditorRecoveryAttachment
import dev.renkinProject.renkin.ui.EditorRecoveryPreparation
import dev.renkinProject.renkin.ui.EditorRenderWitness
import dev.renkinProject.renkin.ui.EditorSession
import dev.renkinProject.renkin.ui.PreparedEditorRecovery
import dev.renkinProject.renkin.ui.bindEditorRecovery
import dev.renkinProject.renkin.ui.canConfirmDraft
import dev.renkinProject.renkin.ui.captureRenderWitness
import dev.renkinProject.renkin.ui.renderWitnessOrNull
import dev.renkinProject.renkin.ui.requirePublishedSources
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class EditorSessionLifetime(val generation: Long, val sessionId: String?)

internal data class EditorAutosaveFrame(
    val lifetime: EditorSessionLifetime,
    val owner: ProfileSessionToken,
    val storageEpoch: Long,
    val serial: Long?,
    val witness: EditorRenderWitness?,
    val content: EditorAutosaveContent?,
    val ready: Boolean,
    val unchangedFromOpening: Boolean
) {
    internal fun sameContentAs(other: EditorAutosaveFrame?): Boolean =
        other != null && lifetime == other.lifetime && owner == other.owner && storageEpoch == other.storageEpoch &&
            content != null && content == other.content
}

@HiltViewModel
class EditorSessionViewModel @Inject constructor() : ViewModel() {
    private data class ActiveSession(
        val id: String, val state: EditorSession, val storageEpoch: Long = StorageOperationGate.epoch
    )
    private var activeSession: ActiveSession? = null
    private var restoreJob: Job? = null
    private var restoreGeneration = 0L
    private var sessionGeneration = 0L
    private var cleared = false
    private val recoveryOwner = Any()
    private val captureMutex = Mutex()
    private val mutableRecoveryAttachment = MutableStateFlow<EditorRecoveryAttachment?>(null)
    internal val recoveryAttachment = mutableRecoveryAttachment.asStateFlow()
    private val mutableLifetime = MutableStateFlow(EditorSessionLifetime(0, null))
    internal val lifetime = mutableLifetime.asStateFlow()

    // Preparation never attaches a session; the offer owner must retain or dispose Ready.
    internal suspend fun prepareDraftRecovery(
        expected: EditorDraftCheckpoint,
        provider: ApplicationProvider,
        store: EditorDraftStore,
        resources: EditorRecoveryResources
    ): EditorRecoveryPreparation = captureMutex.withLock {
        var unattached: BoundEditorRecovery? = null
        var retained = false
        try {
            val generation = withContext(Dispatchers.Main.immediate) {
                sessionGeneration.takeIf { !cleared && activeSession == null }
            } ?: return@withLock EditorRecoveryPreparation.Superseded
            val candidate = store.candidate() ?: return@withLock if (store.checkpoint() == expected) {
                EditorRecoveryPreparation.Empty
            } else EditorRecoveryPreparation.Superseded
            if (candidate.checkpoint != expected) return@withLock EditorRecoveryPreparation.Superseded
            val owner = candidate.record.owner
            val runtime = provider.runtimeState.value
            if (!runtime.ready) return@withLock EditorRecoveryPreparation.Superseded
            if (runtime.activeProfileId != owner.profileId) return@withLock EditorRecoveryPreparation.NeedsProfile(owner.profileId)
            val baseline = provider.captureEditorRecoveryBaseline(runtime.sessionToken, owner.packageName, owner.activityName)
            val fingerprintsMatch = baseline != null &&
                EditorDraftFingerprints.profile(baseline.profile) == owner.profileFingerprint &&
                EditorDraftFingerprints.icon(owner.profileId, owner.packageName, owner.activityName, baseline.row) == owner.originalIconFingerprint
            if ((baseline != null && !provider.validateEditorDraftBaseline(baseline)) || store.checkpoint() != expected) {
                return@withLock EditorRecoveryPreparation.Superseded
            }
            val ownerUnchanged = withContext(Dispatchers.Main.immediate) {
                activeSession == null && sessionGeneration == generation && provider.isCurrentSession(runtime.sessionToken)
            }
            if (!ownerUnchanged) return@withLock EditorRecoveryPreparation.Superseded
            if (baseline == null || !fingerprintsMatch) return@withLock EditorRecoveryPreparation.Obsolete
            val payloads = linkedMapOf<String, ByteArray>()
            for (artifact in candidate.record.artifacts) payloads[artifact.name] = store.artifact(expected, artifact.name)
            val capture = EditorCompleteCodec.decode(payloads)
            if (!provider.validateEditorDraftBaseline(baseline) || store.checkpoint() != expected) {
                return@withLock EditorRecoveryPreparation.Superseded
            }
            val bound = bindEditorRecovery(capture, resources)
            unattached = bound
            val editorUnchanged = withContext(Dispatchers.Main.immediate) {
                activeSession == null && sessionGeneration == generation
            }
            if (!editorUnchanged || !provider.validateEditorDraftBaseline(baseline) || store.checkpoint() != expected) {
                return@withLock EditorRecoveryPreparation.Superseded
            }
            val stillAvailable = withContext(Dispatchers.Main.immediate) {
                activeSession == null && sessionGeneration == generation && provider.isCurrentSession(baseline.session)
            }
            if (!stillAvailable) EditorRecoveryPreparation.Superseded else {
                val result = EditorRecoveryPreparation.Ready(PreparedEditorRecovery(expected, owner, baseline, generation, bound, recoveryOwner))
                retained = true
                result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: EditorDraftChangedException) {
            EditorRecoveryPreparation.Superseded
        } catch (_: ProfileSessionChangedException) {
            EditorRecoveryPreparation.Superseded
        } catch (_: StorageRecoveryUnavailableException) {
            EditorRecoveryPreparation.Superseded
        } catch (error: Exception) {
            Log.error("EditorSessionViewModel", "Editor draft recovery could not be prepared", error)
            EditorRecoveryPreparation.Failed(error)
        } catch (error: OutOfMemoryError) {
            Log.error("EditorSessionViewModel", "Editor draft recovery exceeded available memory", error)
            EditorRecoveryPreparation.Failed(error)
        } finally {
            if (!retained) unattached?.disposeUnattached()
        }
    }

    // The offer owner observes recoveryAttachment even if cancellation loses the returned Accepted.
    internal suspend fun acceptDraftRecovery(
        id: String,
        prepared: PreparedEditorRecovery,
        provider: ApplicationProvider,
        store: EditorDraftStore
    ): EditorRecoveryAcceptance = captureMutex.withLock {
        var published = false
        var accepted: EditorRecoveryAcceptance.Accepted? = null
        try {
            val available = withContext(Dispatchers.Main.immediate) { canAcceptRecovery(prepared) }
            if (!available) return@withLock EditorRecoveryAcceptance.Superseded
            // Release the store mutex before constructing the session or acquiring domain gates.
            val lease = store.acceptanceLease(prepared.checkpoint)
                ?: return@withLock EditorRecoveryAcceptance.Superseded
            val active = withContext(Dispatchers.Main.immediate) {
                if (!canAcceptRecovery(prepared)) null
                else ActiveSession(id, prepared.sessionForAcceptance())
            } ?: return@withLock EditorRecoveryAcceptance.Superseded
            val result = EditorRecoveryAcceptance.Accepted(EditorRecoveryAttachment(
                id, active.state.application.packageName, active.state.application.activityName, active.state.owner,
                prepared.bound.requested.export.themed))
            accepted = result
            val caller = currentCoroutineContext()
            val attached = provider.publishEditorDraftRecovery(prepared.baseline) {
                lease.publishIfCurrent {
                    caller.ensureActive()
                    if (!canAcceptRecovery(prepared) || !prepared.transferToEditor(active.state)) false
                    else {
                        sessionGeneration++
                        activeSession = active
                        published = true
                        mutableRecoveryAttachment.value = result.attachment
                        publishLifetime()
                        true
                    }
                }
            }
            if (attached) result else EditorRecoveryAcceptance.Superseded
        } catch (error: CancellationException) {
            throw error
        } catch (_: ProfileOperationSupersededException) {
            if (published) checkNotNull(accepted) else EditorRecoveryAcceptance.Superseded
        } catch (_: ProfileSessionChangedException) {
            if (published) checkNotNull(accepted) else EditorRecoveryAcceptance.Superseded
        } catch (_: StorageRecoveryUnavailableException) {
            if (published) checkNotNull(accepted) else EditorRecoveryAcceptance.Superseded
        } catch (error: Exception) {
            if (published) checkNotNull(accepted) else {
                Log.error("EditorSessionViewModel", "Editor draft recovery could not be accepted", error)
                EditorRecoveryAcceptance.Failed(error)
            }
        } catch (error: OutOfMemoryError) {
            if (published) checkNotNull(accepted) else {
                Log.error("EditorSessionViewModel", "Editor draft acceptance exceeded available memory", error)
                EditorRecoveryAcceptance.Failed(error)
            }
        }
    }

    @MainThread
    private fun canAcceptRecovery(prepared: PreparedEditorRecovery): Boolean =
        !cleared && activeSession == null && prepared.editorOwner === recoveryOwner &&
            sessionGeneration == prepared.editorGeneration && prepared.canTransfer

    // The lifecycle owner debounces before entering this Main pixel-copy boundary.
    internal suspend fun captureCompleteDraft(
        id: String,
        expected: EditorDraftCheckpoint,
        provider: ApplicationProvider,
        store: EditorDraftStore,
        resources: EditorDraftResources,
        inputs: IconModifierInputStore
    ): EditorCaptureResult = captureMutex.withLock {
        val started = SystemClock.elapsedRealtimeNanos()
        var mainCopyNanos = 0L
        try {
            val active = withContext(Dispatchers.Main.immediate) { activeSession?.takeIf { it.id == id } }
                ?: return@withLock EditorCaptureResult.Superseded
            if (store.checkpoint() != expected) return@withLock EditorCaptureResult.Superseded
            val baseline = provider.captureEditorDraftBaseline(active.state.owner, active.state.baselineApplication)
            val selections = withContext(Dispatchers.Main.immediate) {
                if (activeSession !== active) throw EditorCapturePendingException()
                val witness = active.state.captureRenderWitness().also {
                    active.state.requirePublishedSources(it)
                    active.state.draft.captureState(it)
                }
                Triple(witness, active.state.recoveryResources?.font(witness.controls.create.fontReference),
                    witness.upload.path?.takeIf { active.state.upload.ownedImage != null }
                        ?.let { active.state.recoveryResources?.upload(it) })
            }
            val witness = selections.first
            val owned = linkedMapOf("font" to (selections.second
                ?: resources.font(witness.controls.create.fontReference)))
            witness.upload.path?.let { owned["upload"] = selections.third ?: resources.upload(it) }
            val captured = withContext(Dispatchers.Main.immediate) {
                if (activeSession !== active || active.state.captureRenderWitness() != witness) throw EditorCapturePendingException()
                val copyStarted = SystemClock.elapsedRealtimeNanos()
                try { EditorCompleteMainCapture.capture(active.state, owned.values.sumOf { it.byteCount.toLong() }) }
                finally { mainCopyNanos += SystemClock.elapsedRealtimeNanos() - copyStarted }
            }
            val owner = EditorDraftOwner(active.state.owner.profileId, active.state.application.packageName,
                active.state.application.activityName, EditorDraftFingerprints.profile(baseline.profile),
                EditorDraftFingerprints.icon(baseline.session.profileId, baseline.application.packageName,
                    baseline.application.activityName, baseline.row))
            for (pending in captured.pendingInputs) {
                val encoded = requireNotNull(inputs.read(pending.retained)) { "Editor modifier input file is unavailable" }
                val icon = requireNotNull(inputs.decode(encoded)) { "Editor modifier input file is invalid" }
                withContext(Dispatchers.Main.immediate) {
                    val copyStarted = SystemClock.elapsedRealtimeNanos()
                    try { captured.attachInput(pending.slot, icon) }
                    finally { mainCopyNanos += SystemClock.elapsedRealtimeNanos() - copyStarted }
                }
            }
            val detached = withContext(Dispatchers.Main.immediate) { captured.finish(owned) }
            val payloads = EditorCompleteCodec.encode(detached)
            if (BuildConfig.DEBUG) {
                val runtime = Runtime.getRuntime()
                Log.debug("EditorDraftCapture", "mainCopyMs=${mainCopyNanos / 1_000_000.0} " +
                    "captureMs=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0} " +
                    "payloadBytes=${payloads.values.sumOf { it.size.toLong() }} " +
                    "heapBytes=${runtime.totalMemory() - runtime.freeMemory()} nativeBytes=${Debug.getNativeHeapAllocatedSize()}")
            }
            if (!provider.validateEditorDraftBaseline(baseline) || store.checkpoint() != expected) {
                return@withLock EditorCaptureResult.Superseded
            }
            val current = withContext(Dispatchers.Main.immediate) {
                activeSession === active && run {
                    val witness = active.state.captureRenderWitness()
                    active.state.requirePublishedSources(witness)
                    witness == captured.witness && active.state.draft.captureState(witness).serial == captured.serial
                }
            }
            if (!current) EditorCaptureResult.Superseded else EditorCaptureResult.Ready(
                PreparedEditorDraft(expected, owner, baseline, captured.serial, payloads))
        } catch (error: CancellationException) {
            throw error
        } catch (_: EditorCapturePendingException) {
            EditorCaptureResult.Superseded
        } catch (_: ProfileOperationSupersededException) {
            EditorCaptureResult.Superseded
        } catch (_: ProfileSessionChangedException) {
            EditorCaptureResult.Superseded
        } catch (error: Exception) {
            Log.error("EditorSessionViewModel", "Complete editor draft could not be captured", error)
            EditorCaptureResult.Failed(error)
        } catch (error: OutOfMemoryError) {
            Log.error("EditorSessionViewModel", "Complete editor draft exceeds available memory", error)
            EditorCaptureResult.Failed(error)
        }
    }

    @MainThread
    internal fun restoreModifiers(id: String, load: suspend (EditorSession) -> PreparedModifierReplay?) {
        val active = activeSession?.takeIf { it.id == id && it.state.draft.canRestore } ?: return
        cancelModifierRestore(id)
        val generation = ++restoreGeneration
        active.state.restoringModifiers = true
        active.state.modifierRestoreFailed = false
        restoreJob = viewModelScope.launch {
            try {
                val prepared = load(active.state)
                if (activeSession === active && generation == restoreGeneration && active.state.draft.canRestore) {
                    if (prepared != null && prepared.storageEpoch == StorageOperationGate.epoch) {
                        active.state.restoreModifiers(prepared.verified)
                    } else active.state.modifierRestoreFailed = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("EditorSessionViewModel", "Saved modifiers could not be restored", error)
                if (activeSession === active && generation == restoreGeneration) active.state.modifierRestoreFailed = true
            } finally {
                if (activeSession === active && generation == restoreGeneration) active.state.restoringModifiers = false
            }
        }
    }

    @MainThread
    internal fun cancelModifierRestore(id: String) {
        if (activeSession?.id != id) return
        restoreGeneration++
        restoreJob?.cancel()
        restoreJob = null
        activeSession?.state?.restoringModifiers = false
    }

    @MainThread
    internal fun getOrCreate(
        id: String,
        owner: ProfileSessionToken,
        app: PackageInfoStruct,
        initialPack: String,
        initialFont: String,
        allowCreate: Boolean
    ): EditorSession? {
        activeSession?.takeIf {
            it.id == id && it.state.owner == owner && it.state.application.key == app.key
        }?.let { return it.state }
        if (!allowCreate) return null
        activeSession?.id?.let(::cancelModifierRestore)
        sessionGeneration++
        mutableRecoveryAttachment.value = null
        return EditorSession(owner, app, initialPack, initialFont).also {
            activeSession = ActiveSession(id, it)
            publishLifetime()
        }
    }

    @MainThread
    internal fun recoveredSession(attachment: EditorRecoveryAttachment): EditorSession? =
        activeSession?.takeIf {
            mutableRecoveryAttachment.value == attachment && it.id == attachment.sessionId &&
                it.state.owner == attachment.owner
        }?.state

    @MainThread
    internal fun autosaveFrame(): EditorAutosaveFrame? {
        val active = activeSession ?: return null
        val witness = active.state.draft.completedFrameWitness
        return EditorAutosaveFrame(mutableLifetime.value, active.state.owner, active.storageEpoch,
            active.state.draft.completedFrameSerial, witness, active.state.draft.completedAutosaveContent,
            active.state.draft.completedFrameSerial != null && active.state.draft.iconToConfirm != null &&
                !active.state.draft.generating && !active.state.restoringModifiers &&
                (witness == null || active.state.canConfirmDraft(witness)),
            active.state.draft.unchangedFromOpening)
    }

    @MainThread
    internal fun initialAutosaveFrame(): EditorAutosaveFrame? {
        val draft = activeSession?.state?.draft ?: return null
        val serial = draft.initialFrameSerial ?: return null
        return autosaveFrame()?.copy(serial = serial, witness = draft.initialFrameWitness, content = draft.initialAutosaveContent,
            ready = true, unchangedFromOpening = true)
    }

    @MainThread
    internal fun ownsAutosaveFrame(frame: EditorAutosaveFrame): Boolean {
        if (cleared || !frame.ready || autosaveFrame() != frame) return false
        // The observer reads published witnesses cheaply; live controls are checked only at capture.
        return activeSession?.state?.renderWitnessOrNull() == frame.witness
    }

    @MainThread
    private fun publishLifetime() {
        mutableLifetime.value = EditorSessionLifetime(sessionGeneration, activeSession?.id)
    }

    @MainThread
    internal fun release(id: String) {
        cancelModifierRestore(id)
        if (activeSession?.id == id) {
            sessionGeneration++
            activeSession = null
            mutableRecoveryAttachment.value = null
            publishLifetime()
        }
    }

    override fun onCleared() {
        restoreJob?.cancel()
        cleared = true
        sessionGeneration++
        activeSession = null
        mutableRecoveryAttachment.value = null
        publishLifetime()
    }
}
