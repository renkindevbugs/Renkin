package dev.renkinProject.renkin.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Persists the sets of generated icons (one per profile), so they survive app restarts.
 * Wraps the Room DAOs and runs every access off the main thread.
 */
class RenkinPackRepository(private val db: RenkinPackDatabase) {
    /** Production entry point: uses the shared singleton database. Tests pass an in-memory
     * [RenkinPackDatabase] to the primary constructor instead. */
    constructor(context: Context) : this(RenkinPackDatabase.get(context))

    private val dao = db.renkinPackDao()
    private val profileDao = db.profileDao()

    suspend fun getAll(profileId: Long): List<DbApplication> = withContext(Dispatchers.Default) {
        dao.getAll(profileId)
    }

    /**
     * Replaces the stored set of [profileId] in one shot — atomically: a crash or error
     * between the delete and the insert must not leave the profile empty.
     */
    suspend fun replaceAll(profileId: Long, apps: List<DbApplication>) = db.withTransaction {
        dao.deleteAllApplications(profileId)
        dao.insertAll(apps)
    }

    /** Every profile's stored icons in one read — backup export. */
    suspend fun getAllProfilesApplications(): List<DbApplication> = withContext(Dispatchers.Default) {
        dao.getAllProfiles()
    }

    /**
     * Backup import: replaces every profile and every stored icon in one transaction, keeping
     * the backup's profile ids (icons and watch rules reference them). Callers must have fully
     * parsed the incoming data first — a decode error must not run after the wipe.
     */
    suspend fun replaceEverything(profiles: List<Profile>, apps: List<DbApplication>) = db.withTransaction {
        dao.deleteEverything()
        profileDao.deleteEverything()
        profiles.forEach { profileDao.insert(it) }
        dao.insertAll(apps)
    }

    // ---- Pack verdicts -------------------------------------------------------------

    private val verdictDao = db.packVerdictDao()

    suspend fun verdicts(packages: List<String>): Map<String, PackVerdict> = withContext(Dispatchers.Default) {
        verdictDao.get(packages).associateBy { it.packageName }
    }

    suspend fun allVerdicts(): List<PackVerdict> = withContext(Dispatchers.Default) {
        verdictDao.getAll()
    }

    suspend fun upsertVerdicts(verdicts: List<PackVerdict>) = withContext(Dispatchers.Default) {
        if (verdicts.isNotEmpty()) verdictDao.upsert(verdicts)
    }

    /**
     * Rebuilds the per-device verdict cache from scratch, keeping only what is actually
     * installed now (marked owned). Used after a full-backup restore: the cache is device-local
     * truth about which packs are owned/priced HERE and must not survive a restore that replaced
     * everything else — otherwise a pack the device no longer has installed would stay unlocked.
     */
    suspend fun resetVerdictsToInstalled(installed: List<IconPack>) = withContext(Dispatchers.Default) {
        verdictDao.deleteEverything()
        if (installed.isNotEmpty()) {
            verdictDao.upsert(installed.map {
                PackVerdict(it.packageName, seenInstalled = true, label = it.applicationName)
            })
        }
    }

    /** Every pack any stored icon (any profile) came from. */
    suspend fun distinctSourcePacks(): List<String> = withContext(Dispatchers.Default) {
        dao.distinctSourcePacks()
    }

    // ---- Profiles -----------------------------------------------------------------

    /** All profiles, default first (ordered by id), as a reactive stream for the switcher UI. */
    fun profilesFlow(): Flow<List<Profile>> = profileDao.getAllFlow()

    suspend fun profiles(): List<Profile> = withContext(Dispatchers.Default) { profileDao.getAll() }

    suspend fun profile(id: Long): Profile? = withContext(Dispatchers.Default) { profileDao.get(id) }

    /** Restores the undeletable default row without touching its icons or an existing profile. */
    suspend fun ensureDefaultProfile(): Profile = withContext(Dispatchers.Default) {
        profileDao.insertIfMissing(
            Profile(id = DEFAULT_PROFILE_ID, name = "Renkin", packLabel = "Renkin Pack")
        )
        checkNotNull(profileDao.get(DEFAULT_PROFILE_ID))
    }

    suspend fun createProfile(profile: Profile): Long = withContext(Dispatchers.Default) {
        profileDao.insert(profile)
    }

    suspend fun updateProfile(profile: Profile) = withContext(Dispatchers.Default) {
        profileDao.update(profile)
    }

    /** Deletes the profile and all of its stored icons. The default profile is never deleted. */
    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.Default) {
        if (id == DEFAULT_PROFILE_ID) return@withContext
        dao.deleteAllApplications(id)
        profileDao.delete(id)
    }
}
