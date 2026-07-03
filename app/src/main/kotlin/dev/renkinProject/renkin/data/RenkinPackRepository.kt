package dev.renkinProject.renkin.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the set of generated icons (the "Renkin pack") the user has built, so they
 * survive app restarts. Wraps the Room DAO and runs every access off the main thread.
 */
class RenkinPackRepository(private val db: RenkinPackDatabase) {
    /** Production entry point: uses the shared singleton database. Tests pass an in-memory
     * [RenkinPackDatabase] to the primary constructor instead. */
    constructor(context: Context) : this(RenkinPackDatabase.get(context))

    private val dao = db.renkinPackDao()

    suspend fun getAll(): List<DbApplication> = withContext(Dispatchers.Default) {
        dao.getAll()
    }

    /** Replaces the whole stored set in one shot. */
    suspend fun replaceAll(apps: List<DbApplication>) = withContext(Dispatchers.Default) {
        dao.deleteAllApplications()
        dao.insertAll(apps)
    }
}
