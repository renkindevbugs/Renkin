package dev.renkinProject.renkin

import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
internal class EditorDraftRecoverySession @Inject constructor() {
    data class Postponed(val checkpoint: EditorDraftCheckpoint?)

    private val mutablePostponed = MutableStateFlow<Postponed?>(null)
    val postponed = mutablePostponed.asStateFlow()

    fun postpone(checkpoint: EditorDraftCheckpoint?) {
        mutablePostponed.value = Postponed(checkpoint)
    }

    fun review() {
        mutablePostponed.value = null
    }
}
