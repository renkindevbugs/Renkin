package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain writers call these while holding the storage gate, before replacing any profile data.
 * Covers every local recovery copy of unsaved work: the editor draft and the unsaved-icon journal.
 */
@Singleton
class EditorDraftInvalidation @Inject internal constructor(
    private val store: EditorDraftStore,
    private val journal: UnsavedIconJournalStore
) {
    suspend fun beforeStorageReplacement() {
        journal.invalidateAll()
        store.invalidateStorage()
    }

    suspend fun duringStorageRecovery() {
        journal.invalidateAll()
        store.invalidateForRecovery()
    }

    suspend fun beforeProfileDeletion(profileId: Long) {
        journal.discardProfile(profileId)
        store.invalidateProfile(profileId)
    }

    /** Saved rows supersede icons a postponed journal offer would restore into that profile. */
    suspend fun afterProfileSaved(profileId: Long) {
        journal.discardProfile(profileId)
    }

    /** Modifier inputs only the unsaved-icon journal still references. */
    suspend fun retainedModifierInputs(): List<String> = journal.modifierInputDocuments()
}
