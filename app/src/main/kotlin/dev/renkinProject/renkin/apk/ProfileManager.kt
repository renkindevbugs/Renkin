package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.BuiltPrimaryIconPackKey
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.persistBuiltPrimaryPrefs
import dev.renkinProject.renkin.data.switchProfilePrefs
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.dataStore
import kotlinx.coroutines.flow.first

/**
 * Owns which profile is active and everything stored ABOUT profiles: CRUD, the per-profile
 * preference snapshots (each profile freezes the generation prefs it was left with), and the
 * per-profile pack package name. Swapping the in-memory icon set on a switch stays in
 * [ApplicationProvider] — it owns the app list; this class flips the persistent state.
 */
class ProfileManager internal constructor(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val store: DataStore<Preferences>
) {
    constructor(context: Context, packRepo: RenkinPackRepository) :
        this(context, packRepo, context.dataStore)

    /** The profile whose icons/preferences are active. Set before the saved pack loads. */
    var activeProfileId: Long by mutableStateOf(DEFAULT_PROFILE_ID)
        private set

    /** Reads the persisted active id at startup (before the saved pack loads). */
    suspend fun initActiveId() {
        reloadActiveId()
    }

    fun profilesFlow() = packRepo.profilesFlow()

    suspend fun activeProfile(): Profile? = packRepo.profile(activeProfileId)

    suspend fun profileExists(id: Long): Boolean = packRepo.profile(id) != null

    /** The package name [profileId]'s pack builds under (base pack for the default). */
    fun packPackageNameFor(profileId: Long): String =
        if (profileId == DEFAULT_PROFILE_ID) IconPackBuilder.PACKAGE_NAME
        else "${IconPackBuilder.PACKAGE_NAME}.p$profileId"

    /** Creates a profile and returns its id. */
    suspend fun createProfile(name: String, description: String, packLabel: String): Long =
        packRepo.createProfile(Profile(name = name, description = description, packLabel = packLabel))

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
        // Drop the profile's watch rules too, or the periodic worker keeps checking them and
        // fires notifications that deep-link into a profile that no longer exists.
        WatchRepository(context).deleteRulesForProfile(id)
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
        if (profileId == activeProfileId) {
            store.persistBuiltPrimaryPrefs(source)
            return
        }
        val profile = packRepo.profile(profileId) ?: return
        val snapshot = runCatching { org.json.JSONObject(profile.prefsSnapshot) }
            .getOrDefault(org.json.JSONObject())
        snapshot.put(
            BuiltPrimarySourceKey.name,
            source.getIntValue(PrimarySourceKey, SOURCE_DEFAULT.ordinal)
        )
        snapshot.put(
            BuiltPrimaryIconPackKey.name,
            source.getStringValue(PrimaryIconPackKey)
        )
        packRepo.updateProfile(profile.copy(prefsSnapshot = snapshot.toString()))
    }

    /** Persists the active profile's "don't show the missing-packs dialog again" choice. */
    suspend fun setHideMissingPackWarning(hide: Boolean) {
        packRepo.profile(activeProfileId)?.let {
            packRepo.updateProfile(it.copy(hideMissingPackWarning = hide))
        }
    }

    /**
     * Flips the persistent side of a profile switch: snapshots the leaving profile's
     * generation preferences, restores the target's, and records the new active id.
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
        val storedId = store.data.first()[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        activeProfileId = if (packRepo.profile(storedId) != null) storedId else DEFAULT_PROFILE_ID
        if (activeProfileId != storedId) {
            store.edit { it[ActiveProfileIdKey] = activeProfileId }
        }
    }
}
