package dev.renkinProject.renkin

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.EditorSession
import javax.inject.Inject

@HiltViewModel
class EditorSessionViewModel @Inject constructor() : ViewModel() {
    private data class ActiveSession(val id: String, val state: EditorSession)
    private var activeSession: ActiveSession? = null

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
        return EditorSession(owner, app, initialPack, initialFont).also {
            activeSession = ActiveSession(id, it)
        }
    }

    @MainThread
    internal fun release(id: String) {
        if (activeSession?.id == id) activeSession = null
    }

    override fun onCleared() {
        activeSession = null
    }
}
