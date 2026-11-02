package dev.renkinProject.renkin.data

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One unsaved hand-made icon, keyed "package/activity"; [row] is null when the icon was cleared. */
data class UnsavedIconJournalEntry(val key: String, val row: DbApplication?)

/**
 * Hand-made icons a process had not built or saved yet. Refresh output is deliberately absent:
 * it is cheap to regenerate and too large to keep. [droppedCount] rows exceeded the size cap.
 */
data class UnsavedIconJournal(
    val providerInstanceId: String,
    val profileId: Long,
    val entries: List<UnsavedIconJournalEntry>,
    val droppedCount: Int = 0
)

/**
 * Keeps unsaved hand-made icons outside Room, so a process Android ends in the background can
 * offer them back. `current` belongs to the running process; at startup the previous process's
 * journal moves to `offer`, where it waits for Restore or Discard. Never part of a save, a
 * backup or the install-confirmation snapshot.
 */
@Singleton
class UnsavedIconJournalStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val root by lazy { File(context.noBackupFilesDir, "unsaved-journal") }
    private val current by lazy { AtomicFile(File(root, "current.json")) }
    private val offer by lazy { AtomicFile(File(root, "offer.json")) }
    // AtomicFile.openRead deletes an unfinished write, so reads must not overlap writes.
    private val mutex = Mutex()

    /** Replaces the running process's journal; an empty journal removes it. */
    suspend fun writeCurrent(journal: UnsavedIconJournal) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (journal.entries.isEmpty()) current.delete()
            else write(current, UnsavedIconJournalCodec.encode(journal, MAX_JOURNAL_CHARS))
        }
    }

    /**
     * Moves a journal left by an ended process into the offer slot and returns the offer.
     * A journal from [currentInstance] is this process's own and stays where it is.
     */
    suspend fun takeOverPrevious(currentInstance: String): UnsavedIconJournal? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = read(current)
            if (previous != null && previous.providerInstanceId != currentInstance) {
                if (previous.entries.isNotEmpty()) {
                    write(offer, UnsavedIconJournalCodec.encode(previous, MAX_JOURNAL_CHARS))
                }
                current.delete()
            }
            read(offer)
        }
    }

    suspend fun discardOffer() = mutex.withLock { withContext(Dispatchers.IO) { offer.delete() } }

    /** A save of [profileId] supersedes what its journals would restore. */
    suspend fun discardProfile(profileId: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (read(offer)?.profileId == profileId) offer.delete()
        }
    }

    /** Storage replacement or recovery: no journal may revive rows from the replaced data. */
    suspend fun invalidateAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            current.delete()
            offer.delete()
        }
    }

    /** Modifier-input documents the journals reference; startup input cleanup must keep them. */
    suspend fun modifierInputDocuments(): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            listOfNotNull(read(current), read(offer))
                .flatMap { journal -> journal.entries.mapNotNull { it.row?.modifierInput?.ifEmpty { null } } }
        }
    }

    private fun read(file: AtomicFile): UnsavedIconJournal? {
        if (!file.baseFile.exists()) return null
        return try {
            UnsavedIconJournalCodec.decode(String(file.readFully(), Charsets.UTF_8))
        } catch (error: Exception) {
            Log.error(TAG, "Unsaved icon journal is unreadable", error)
            file.delete()
            null
        }
    }

    private fun write(file: AtomicFile, text: String) {
        if (root.mkdirs()) syncDirectory(context.noBackupFilesDir)
        file.writeVerified(text.toByteArray(Charsets.UTF_8))
        syncDirectory(root)
    }

    private companion object {
        const val TAG = "UnsavedIconJournal"
        // Payloads are base64/XML text, so characters are bytes: 48 MiB.
        const val MAX_JOURNAL_CHARS = 48 * 1024 * 1024
    }
}

internal object UnsavedIconJournalCodec {
    private const val VERSION = 1

    /** Rows beyond [maxChars] are left out and counted, never truncated mid-row. */
    fun encode(journal: UnsavedIconJournal, maxChars: Int): String {
        val entries = JSONArray()
        var used = 0
        var dropped = journal.droppedCount
        for (entry in journal.entries) {
            val json = JSONObject().put("key", entry.key)
            entry.row?.let { json.put("row", encodeRow(it)) }
            val size = json.toString().length
            if (used + size > maxChars) {
                dropped++
                continue
            }
            used += size
            entries.put(json)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("providerInstance", journal.providerInstanceId)
            .put("profileId", journal.profileId)
            .put("dropped", dropped)
            .put("entries", entries)
            .toString()
    }

    fun decode(text: String): UnsavedIconJournal {
        val json = JSONObject(text)
        require(json.getInt("version") == VERSION) { "Unsupported journal version" }
        val profileId = json.getLong("profileId")
        val array = json.getJSONArray("entries")
        return UnsavedIconJournal(
            providerInstanceId = json.getString("providerInstance"),
            profileId = profileId,
            entries = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                UnsavedIconJournalEntry(
                    entry.getString("key"),
                    entry.optJSONObject("row")?.let { decodeRow(it, profileId) }
                )
            },
            droppedCount = json.optInt("dropped").coerceAtLeast(0)
        )
    }

    // Every DbApplication column except profileId, which the journal header carries.
    fun encodeRow(row: DbApplication): JSONObject = JSONObject()
        .put("packageName", row.packageName)
        .put("activityName", row.activityName)
        .put("isAdaptiveIcon", row.isAdaptiveIcon)
        .put("isXml", row.isXml)
        .put("drawable", row.drawable)
        .put("calendarEnabled", row.calendarEnabled)
        .put("calendarPrefix", row.calendarPrefix)
        .put("calendarPackName", row.calendarPackName)
        .put("sourcePackName", row.sourcePackName)
        .put("sourceDrawableName", row.sourceDrawableName)
        .put("isCustomIcon", row.isCustomIcon)
        .put("isLegacyIcon", row.isLegacyIcon)
        .put("baseDrawable", row.baseDrawable)
        .put("baseIsAdaptiveIcon", row.baseIsAdaptiveIcon)
        .put("baseIsXml", row.baseIsXml)
        .put("sourceUrl", row.sourceUrl)
        .put("isFallbackIcon", row.isFallbackIcon)
        .put("modifierRecipe", row.modifierRecipe)
        .put("modifierInput", row.modifierInput)

    // A journal written before a column existed lacks its key; it reads as the entity default
    // instead of failing, because read() deletes an undecodable journal with the work it holds.
    fun decodeRow(json: JSONObject, profileId: Long): DbApplication {
        val defaults = DbApplication(
            packageName = json.getString("packageName"),
            activityName = json.getString("activityName"),
            isAdaptiveIcon = json.getBoolean("isAdaptiveIcon"),
            isXml = json.getBoolean("isXml"),
            drawable = json.getString("drawable"),
            profileId = profileId
        )
        return defaults.copy(
            calendarEnabled = json.optBoolean("calendarEnabled", defaults.calendarEnabled),
            calendarPrefix = json.optString("calendarPrefix", defaults.calendarPrefix),
            calendarPackName = json.optString("calendarPackName", defaults.calendarPackName),
            sourcePackName = json.optString("sourcePackName", defaults.sourcePackName),
            sourceDrawableName = json.optString("sourceDrawableName", defaults.sourceDrawableName),
            isCustomIcon = json.optBoolean("isCustomIcon", defaults.isCustomIcon),
            isLegacyIcon = json.optBoolean("isLegacyIcon", defaults.isLegacyIcon),
            baseDrawable = json.optString("baseDrawable", defaults.baseDrawable),
            baseIsAdaptiveIcon = json.optBoolean("baseIsAdaptiveIcon", defaults.baseIsAdaptiveIcon),
            baseIsXml = json.optBoolean("baseIsXml", defaults.baseIsXml),
            sourceUrl = json.optString("sourceUrl", defaults.sourceUrl),
            isFallbackIcon = json.optBoolean("isFallbackIcon", defaults.isFallbackIcon),
            modifierRecipe = json.optString("modifierRecipe", defaults.modifierRecipe),
            modifierInput = json.optString("modifierInput", defaults.modifierInput)
        )
    }
}
