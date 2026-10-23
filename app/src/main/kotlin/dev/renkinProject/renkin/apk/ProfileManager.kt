package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.setPreferenceValue
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.ProfileStyleAccess
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.persistBuiltPrimaryPrefs
import dev.renkinProject.renkin.data.switchProfilePrefs
import dev.renkinProject.renkin.data.withBuiltPrimaryPrefs
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.service.deleteRulesAndNotifications

/**
 * Owns persistent profile selection and stored profile metadata: CRUD, the per-profile
 * style selection and the per-profile pack package name. The shared ProfileStyleStore owns
 * typed style persistence and the transitional preference projection. Swapping the live icons stays in
 * [ApplicationProvider] — it owns the app list; this class flips the persistent state.
 */
class ProfileManager internal constructor(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val store: DataStore<Preferences>,
    private val watchRepo: WatchRepository = WatchRepository(context)
) {
    constructor(context: Context, packRepo: RenkinPackRepository, watchRepo: WatchRepository) :
        this(context, packRepo, context.dataStore, watchRepo)

    /**
     * Persistence-operation selection; it can lead the published id during a gated switch.
     * UI and session ownership checks read [ApplicationProvider.runtimeState], never this field.
     */
    @Volatile var activeProfileId: Long = DEFAULT_PROFILE_ID
        private set

    /** Reads the persisted active id at startup (before the saved pack loads). */
    suspend fun initActiveId() {
        reloadActiveId()
    }

    fun profilesFlow() = packRepo.profilesFlow()

    suspend fun activeProfile(): Profile? = packRepo.profile(activeProfileId)

    suspend fun profileExists(id: Long): Boolean = packRepo.profile(id) != null

    suspend fun profile(id: Long): Profile? = packRepo.profile(id)

    /** The package name [profileId]'s pack builds under (base pack for the default). */
    fun packPackageNameFor(profileId: Long): String =
        if (profileId == DEFAULT_PROFILE_ID) IconPackBuilder.PACKAGE_NAME
        else "${IconPackBuilder.PACKAGE_NAME}.p$profileId"

    /** Creates a profile and returns its id. */
    suspend fun createProfile(name: String, description: String, packLabel: String): Long =
        StorageOperationGate.run {
            val profile = Profile(name = name, description = description, packLabel = packLabel)
            ProfileStyleAccess.ownerFor(store)?.createProfile(profile) ?: packRepo.createProfile(profile)
        }

    /**
     * Updates [id]'s user-facing details. The pack label only takes effect on the next build
     * (it's the launcher-visible label baked into the pack APK's manifest).
     */
    suspend fun updateProfileDetails(id: Long, name: String, description: String, packLabel: String) {
        val profile = packRepo.profile(id) ?: return
        packRepo.updateProfile(profile.copy(name = name, description = description, packLabel = packLabel))
    }

    /**
     * Deletes [id]'s stored profile and its watch rules. The caller must switch away first
     * when [id] is active — this only removes persistent state.
     */
    suspend fun deleteProfile(id: Long) {
        packRepo.deleteProfile(id)
        // Drop the profile's watch rules and their notifications too, or the worker keeps
        // checking them. An interruption here is repaired by removeWatchRulesOfDeletedProfiles.
        watchRepo.deleteRulesAndNotifications(context, watchRepo.ruleIdsForProfile(id))
    }

    /** Records whether [profileId]'s save is still waiting for a build. */
    suspend fun markUnbuilt(profileId: Long, unbuilt: Boolean) {
        packRepo.profile(profileId)?.let {
            packRepo.updateProfile(it.copy(hasUnbuiltChanges = unbuilt))
        }
    }

    /**
     * Records the source a successful build used in the profile that owned that build. An
     * inactive profile lives in its Room snapshot; writing the shared DataStore there would
     * otherwise leak the result into whichever profile the user switched to during install.
     */
    suspend fun recordBuiltPrimary(profileId: Long, source: Preferences) {
        ProfileStyleAccess.ownerFor(store)?.let {
            it.recordBuiltPrimary(profileId, source)
            return
        }
        if (profileId == activeProfileId) {
            store.persistBuiltPrimaryPrefs(source)
            return
        }
        val profile = packRepo.profile(profileId) ?: return
        packRepo.updateProfile(profile.copy(prefsSnapshot = profile.prefsSnapshot.withBuiltPrimaryPrefs(source)))
    }

    /** Persists the active profile's "don't show the missing-packs dialog again" choice. */
    suspend fun setHideMissingPackWarning(hide: Boolean) {
        packRepo.profile(activeProfileId)?.let {
            packRepo.updateProfile(it.copy(hideMissingPackWarning = hide))
        }
    }

    /**
     * Selects the target's persisted style and records its id. Unconverted standalone stores
     * retain the legacy snapshot/swap path through switchProfilePrefs.
     * Returns false when nothing changed (same id, or the target doesn't exist) — the
     * caller only swaps the in-memory icons on true.
     */
    suspend fun switchTo(newProfileId: Long): Boolean {
        if (newProfileId == activeProfileId) return false
        val target = packRepo.profile(newProfileId) ?: return false
        val leaving = packRepo.profile(activeProfileId)
        store.switchProfilePrefs(target.prefsSnapshot, newProfileId) { leavingSnapshot ->
            leaving?.let {
                packRepo.updateProfile(it.copy(prefsSnapshot = leavingSnapshot))
            }
        }
        activeProfileId = newProfileId
        return true
    }

    /**
     * Re-reads the active profile id after a backup import replaced the stores under the
     * running app. The stored id always exists in a well-formed backup; falls back to the
     * default defensively anyway.
     */
    suspend fun reloadActiveId() {
        packRepo.ensureDefaultProfile()
        val storedId = store.getPreferencesAfterPendingWrites()[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        activeProfileId = if (packRepo.profile(storedId) != null) storedId else DEFAULT_PROFILE_ID
        if (activeProfileId != storedId) {
            store.setPreferenceValue(ActiveProfileIdKey, activeProfileId)
        }
    }
}
