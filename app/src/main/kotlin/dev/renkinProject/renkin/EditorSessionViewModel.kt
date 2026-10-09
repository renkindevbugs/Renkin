package dev.renkinProject.renkin

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.StorageOperationGate
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
import dev.renkinProject.renkin.ui.EditorCapturePendingException
import dev.renkinProject.renkin.ui.EditorCompleteMainCapture
import dev.renkinProject.renkin.ui.EditorRecoveryPreparation
import dev.renkinProject.renkin.ui.EditorSession
import dev.renkinProject.renkin.ui.PreparedEditorRecovery
import dev.renkinProject.renkin.ui.bindEditorRecovery
import dev.renkinProject.renkin.ui.captureRenderWitness
import dev.renkinProject.renkin.ui.requirePublishedSources
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class EditorSessionViewModel @Inject constructor() : ViewModel() {
    private data class ActiveSession(val id: String, val state: EditorSession)
    private var activeSession: ActiveSession? = null
    private var restoreJob: Job? = null
    private var restoreGeneration = 0L
    private var sessionGeneration = 0L
    private val captureMutex = Mutex()

    // Dormant until the offer/error/acceptance owner is integrated. This never attaches a session.
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
                sessionGeneration.takeIf { activeSession == null }
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
                val result = EditorRecoveryPreparation.Ready(PreparedEditorRecovery(expected, owner, baseline, generation, bound))
                retained = true
                result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: EditorDraftChangedException) {
            EditorRecoveryPreparation.Superseded
        } catch (_: ProfileSessionChangedException) {
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

    // No lifecycle caller is enabled until 7C3. Debounce must precede this Main pixel copy.
    internal suspend fun captureCompleteDraft(
        id: String,
        expected: EditorDraftCheckpoint,
        provider: ApplicationProvider,
        store: EditorDraftStore,
        resources: EditorDraftResources,
        inputs: IconModifierInputStore
    ): EditorCaptureResult = captureMutex.withLock {
        try {
            val active = withContext(Dispatchers.Main.immediate) { activeSession?.takeIf { it.id == id } }
                ?: return@withLock EditorCaptureResult.Superseded
            if (store.checkpoint() != expected) return@withLock EditorCaptureResult.Superseded
            val baseline = provider.captureEditorDraftBaseline(active.state.owner, active.state.application)
            val witness = withContext(Dispatchers.Main.immediate) {
                if (activeSession !== active) throw EditorCapturePendingException()
                active.state.captureRenderWitness().also {
                    active.state.requirePublishedSources(it)
                    active.state.draft.captureState(it)
                }
            }
            val owned = linkedMapOf("font" to resources.font(witness.controls.create.fontReference))
            witness.upload.path?.let { owned["upload"] = resources.upload(it) }
            val captured = withContext(Dispatchers.Main.immediate) {
                if (activeSession !== active || active.state.captureRenderWitness() != witness) throw EditorCapturePendingException()
                EditorCompleteMainCapture.capture(active.state, owned.values.sumOf { it.byteCount.toLong() })
            }
            val owner = EditorDraftOwner(active.state.owner.profileId, active.state.application.packageName,
                active.state.application.activityName, EditorDraftFingerprints.profile(baseline.profile),
                EditorDraftFingerprints.icon(baseline.session.profileId, baseline.application.packageName,
                    baseline.application.activityName, baseline.row))
            for (pending in captured.pendingInputs) {
                val encoded = requireNotNull(inputs.read(pending.retained)) { "Editor modifier input file is unavailable" }
                val icon = requireNotNull(inputs.decode(encoded)) { "Editor modifier input file is invalid" }
                withContext(Dispatchers.Main.immediate) { captured.attachInput(pending.slot, icon) }
            }
            val detached = withContext(Dispatchers.Main.immediate) { captured.finish(owned) }
            val payloads = EditorCompleteCodec.encode(detached)
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
        return EditorSession(owner, app, initialPack, initialFont).also {
            activeSession = ActiveSession(id, it)
        }
    }

    @MainThread
    internal fun release(id: String) {
        cancelModifierRestore(id)
        if (activeSession?.id == id) {
            sessionGeneration++
            activeSession = null
        }
    }

    override fun onCleared() {
        restoreJob?.cancel()
        sessionGeneration++
        activeSession = null
    }
}
