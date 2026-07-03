package dev.renkinProject.renkin.data

import android.content.Context
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

    /** Replaces the stored set of [profileId] in one shot. */
    suspend fun replaceAll(profileId: Long, apps: List<DbApplication>) = withContext(Dispatchers.Default) {
        dao.deleteAllApplications(profileId)
        dao.insertAll(apps)
    }

    // ---- Profiles -----------------------------------------------------------------

    /** All profiles, default first (ordered by id), as a reactive stream for the switcher UI. */
    fun profilesFlow(): Flow<List<Profile>> = profileDao.getAllFlow()

    suspend fun profiles(): List<Profile> = withContext(Dispatchers.Default) { profileDao.getAll() }

    suspend fun profile(id: Long): Profile? = withContext(Dispatchers.Default) { profileDao.get(id) }

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
