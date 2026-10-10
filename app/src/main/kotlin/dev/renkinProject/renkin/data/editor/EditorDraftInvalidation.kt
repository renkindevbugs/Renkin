package dev.renkinProject.renkin.data.editor

import javax.inject.Inject
import javax.inject.Singleton

/** Domain writers call these while holding the storage gate, before replacing any profile data. */
@Singleton
class EditorDraftInvalidation @Inject internal constructor(private val store: EditorDraftStore) {
    suspend fun beforeStorageReplacement() {
        store.invalidateStorage()
    }

    suspend fun duringStorageRecovery() {
        store.invalidateForRecovery()
    }

    suspend fun beforeProfileDeletion(profileId: Long) {
        store.invalidateProfile(profileId)
    }
}
