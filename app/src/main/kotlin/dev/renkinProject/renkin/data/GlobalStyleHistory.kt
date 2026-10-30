package dev.renkinProject.renkin.data

import androidx.datastore.preferences.core.Preferences
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** Applies kept per profile; older ones are dropped when a new Apply is recorded. */
const val GLOBAL_STYLE_HISTORY_LIMIT = 20

/**
 * One Global style Apply, kept so an earlier look can be loaded again. [style] is a
 * [ProfileStyleCodec] document holding only the sources and global layer that Apply persists:
 * Apply re-renders every icon from its base, so the recipe alone restores the look.
 * Device-local undo history: portable backups and profile shares do not carry it.
 */
@Entity(indices = [Index("profileId")])
data class GlobalStyleHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val appliedAt: Long,
    val style: String
)

@Dao
interface GlobalStyleHistoryDao {
    @Query("SELECT * FROM GlobalStyleHistoryEntry WHERE profileId = :profileId ORDER BY id DESC")
    fun list(profileId: Long): List<GlobalStyleHistoryEntry>

    @Query("SELECT * FROM GlobalStyleHistoryEntry WHERE id = :id")
    fun get(id: Long): GlobalStyleHistoryEntry?

    @Insert
    fun insert(entry: GlobalStyleHistoryEntry): Long

    @Query(
        "DELETE FROM GlobalStyleHistoryEntry WHERE profileId = :profileId AND id NOT IN " +
            "(SELECT id FROM GlobalStyleHistoryEntry WHERE profileId = :profileId ORDER BY id DESC LIMIT :keep)"
    )
    fun trim(profileId: Long, keep: Int)

    @Query("DELETE FROM GlobalStyleHistoryEntry WHERE id = :id AND profileId = :profileId")
    fun delete(profileId: Long, id: Long)

    @Query("DELETE FROM GlobalStyleHistoryEntry WHERE profileId NOT IN (SELECT id FROM Profile)")
    fun deleteForMissingProfiles()

    @Query("DELETE FROM GlobalStyleHistoryEntry")
    fun deleteEverything()
}

/** A decoded [GlobalStyleHistoryEntry]; [recipe] holds only the keys `globalStyleRecipe` keeps. */
data class GlobalStyleHistoryItem(val id: Long, val appliedAt: Long, val recipe: Preferences)
