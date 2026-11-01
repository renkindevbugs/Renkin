package dev.renkinProject.renkin.data

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How much unbuilt, unsaved work a process held. Counts only: the icons themselves are not kept. */
data class UnsavedSessionMarker(
    val providerInstanceId: String,
    val profileId: Long,
    val editedCount: Int,
    val refreshedCount: Int
) {
    val hasChanges: Boolean get() = editedCount > 0 || refreshedCount > 0
}

/**
 * Lets the next process tell the user that Android ended one holding unsaved icons. Lives outside
 * portable and Android backups, and is never part of a profile save.
 */
@Singleton
class UnsavedSessionMarkerStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir, "unsaved-session.json")) }
    // AtomicFile.openRead deletes an unfinished write, so reads must not overlap writes.
    private val mutex = Mutex()

    suspend fun read(): UnsavedSessionMarker? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        try {
            decode(String(file.readFully(), Charsets.UTF_8))
        } catch (error: Exception) {
            Log.error(TAG, "Unsaved session marker is unreadable", error)
            null
        }
    } }

    suspend fun write(marker: UnsavedSessionMarker) = mutex.withLock { withContext(Dispatchers.IO) {
        file.writeVerified(encode(marker).toByteArray(Charsets.UTF_8))
        syncDirectory(context.noBackupFilesDir)
    } }

    private fun encode(marker: UnsavedSessionMarker): String = JSONObject()
        .put(KEY_INSTANCE, marker.providerInstanceId)
        .put(KEY_PROFILE, marker.profileId)
        .put(KEY_EDITED, marker.editedCount)
        .put(KEY_REFRESHED, marker.refreshedCount)
        .toString()

    private fun decode(text: String): UnsavedSessionMarker {
        val json = JSONObject(text)
        return UnsavedSessionMarker(
            providerInstanceId = json.getString(KEY_INSTANCE),
            profileId = json.getLong(KEY_PROFILE),
            editedCount = json.getInt(KEY_EDITED).coerceAtLeast(0),
            refreshedCount = json.getInt(KEY_REFRESHED).coerceAtLeast(0)
        )
    }

    private companion object {
        const val TAG = "UnsavedSessionMarker"
        const val KEY_INSTANCE = "providerInstance"
        const val KEY_PROFILE = "profileId"
        const val KEY_EDITED = "edited"
        const val KEY_REFRESHED = "refreshed"
    }
}
