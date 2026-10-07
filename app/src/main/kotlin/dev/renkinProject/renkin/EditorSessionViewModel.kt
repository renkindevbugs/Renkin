package dev.renkinProject.renkin

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.EditorSession
import javax.inject.Inject
import dev.renkinProject.renkin.icon.creator.PreparedModifierReplay
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class EditorSessionViewModel @Inject constructor() : ViewModel() {
    private data class ActiveSession(val id: String, val state: EditorSession)
    private var activeSession: ActiveSession? = null
    private var restoreJob: Job? = null
    private var restoreGeneration = 0L

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
        return EditorSession(owner, app, initialPack, initialFont).also {
            activeSession = ActiveSession(id, it)
        }
    }

    @MainThread
    internal fun release(id: String) {
        cancelModifierRestore(id)
        if (activeSession?.id == id) activeSession = null
    }

    override fun onCleared() {
        restoreJob?.cancel()
        activeSession = null
    }
}
