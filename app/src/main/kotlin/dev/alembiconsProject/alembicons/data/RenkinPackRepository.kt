package dev.alembiconsProject.alembicons.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the set of generated icons (the "Renkin pack") the user has built, so they
 * survive app restarts. Wraps the Room DAO and runs every access off the main thread.
 */
class RenkinPackRepository(context: Context) {
    private val dao = RenkinPackDatabase.get(context).renkinPackDao()

    suspend fun getAll(): List<DbApplication> = withContext(Dispatchers.Default) {
        dao.getAll()
    }

    /** Replaces the whole stored set in one shot. */
    suspend fun replaceAll(apps: List<DbApplication>) = withContext(Dispatchers.Default) {
        dao.deleteAllApplications()
        dao.insertAll(apps)
    }
}
