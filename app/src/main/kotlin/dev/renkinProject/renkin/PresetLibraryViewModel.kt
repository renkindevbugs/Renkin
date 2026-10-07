package dev.renkinProject.renkin

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PresetLibraryViewModel @Inject constructor(
    private val repository: RenkinPackRepository
) : ViewModel() {
    val colorPresets = repository.colorPresetsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val modifierPresets = repository.modifierPresetsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toastEvents = Channel<Int>(Channel.BUFFERED)
    val toastEvents = _toastEvents.receiveAsFlow()

    fun saveColorPreset(name: String, style: String) {
        writeLibrary("Save color preset", R.string.savedColorsAdded) {
            repository.saveColorPreset(name, style)
        }
    }

    fun deleteColorPreset(id: Long) {
        writeLibrary("Delete color preset", R.string.savedColorsRemoved) {
            repository.deleteColorPreset(id)
        }
    }

    fun saveModifierPreset(name: String, payload: String, schemaVersion: Int) {
        writeLibrary("Save modifier preset", R.string.modifierPresetSaved) {
            repository.saveModifierPreset(name, payload, schemaVersion)
        }
    }

    fun renameModifierPreset(id: Long, name: String) {
        writeLibrary("Rename modifier preset") { repository.renameModifierPreset(id, name) }
    }

    fun updateModifierPreset(id: Long, payload: String, schemaVersion: Int) {
        writeLibrary("Update modifier preset", R.string.modifierPresetUpdated) {
            repository.updateModifierPreset(id, payload, schemaVersion)
        }
    }

    fun markModifierPresetUsed(id: Long) {
        writeLibrary("Mark modifier preset used") { repository.markModifierPresetUsed(id) }
    }

    fun deleteModifierPreset(id: Long) {
        writeLibrary("Delete modifier preset", R.string.modifierPresetRemoved) {
            repository.deleteModifierPreset(id)
        }
    }

    private fun writeLibrary(
        operation: String,
        @StringRes successMessage: Int? = null,
        write: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                write()
                successMessage?.let { _toastEvents.trySend(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("PresetLibraryViewModel", "$operation failed", error)
                _toastEvents.trySend(R.string.presetLibraryWriteFailed)
            }
        }
    }
}
