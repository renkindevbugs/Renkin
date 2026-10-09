package dev.renkinProject.renkin.data.editor

/** Only a synchronous in-memory publication may run here; no I/O or gate acquisition. */
internal class EditorDraftAcceptanceLease(
    private val publish: (() -> Boolean) -> Boolean
) {
    fun publishIfCurrent(action: () -> Boolean): Boolean = publish(action)
}
