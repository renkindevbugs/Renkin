package dev.renkinProject.renkin.data

import android.util.JsonReader
import android.util.JsonWriter
import dev.renkinProject.renkin.extension.toLowerHex
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The icon originals a Global style bake replaced, kept beside its [GlobalStyleHistoryEntry] so
 * the bake can be undone. Device-local like the history. The file is named by the entry id, and
 * its header must also match the entry's profile and time: a restored database can reuse ids.
 * Payloads are streamed, never held as one document, because detailed bitmaps are large.
 */
internal class GlobalStyleBakeStore(private val directory: File) {

    data class Header(val profileId: Long, val appliedAt: Long, val bakedStyle: String)

    /** One icon's base before the bake, and the fingerprint of the base the bake stored instead. */
    data class Original(
        val packageName: String,
        val activityName: String,
        val base: String,
        val baseIsXml: Boolean,
        val baseIsAdaptive: Boolean,
        val bakedFingerprint: String
    )

    /**
     * Written before the bake touches any icon, so a failed write leaves nothing to undo. Each
     * original is taken from [originals] only as it is written, so a lazy source never holds them all.
     */
    fun writePending(header: Header, originals: Iterable<Original>): File {
        directory.mkdirs()
        val pending = File(directory, "$PENDING_PREFIX${header.appliedAt}$SUFFIX")
        JsonWriter(OutputStreamWriter(GZIPOutputStream(pending.outputStream().buffered()), Charsets.UTF_8)).use { writer ->
            writer.beginObject()
            writer.name("version").value(VERSION.toLong())
            writer.name("profileId").value(header.profileId)
            writer.name("appliedAt").value(header.appliedAt)
            writer.name("bakedStyle").value(header.bakedStyle)
            writer.name("icons").beginArray()
            for (original in originals) {
                writer.beginObject()
                writer.name("packageName").value(original.packageName)
                writer.name("activityName").value(original.activityName)
                writer.name("base").value(original.base)
                writer.name("baseIsXml").value(original.baseIsXml)
                writer.name("baseIsAdaptive").value(original.baseIsAdaptive)
                writer.name("bakedFingerprint").value(original.bakedFingerprint)
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        return pending
    }

    fun commitPending(pending: File, entryId: Long) {
        if (!pending.renameTo(file(entryId))) {
            pending.delete()
            throw IOException("Keeping the bake originals failed")
        }
    }

    fun discardPending() {
        directory.listFiles { file -> file.name.startsWith(PENDING_PREFIX) }?.forEach { it.delete() }
    }

    fun header(entry: GlobalStyleHistoryEntry): Header? = openAtIcons(entry)?.let { (reader, header) ->
        reader.close()
        header
    }

    /** How many originals the bake kept, or null when its file is gone or unreadable. */
    fun count(entry: GlobalStyleHistoryEntry): Int? {
        val (reader, _) = openAtIcons(entry) ?: return null
        return reader.use {
            var count = 0
            parsed {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.skipValue()
                    count++
                }
                reader.endArray()
            }.takeIf { it }?.let { count }
        }
    }

    /**
     * Hands each original to [action] as it is read, so only one payload is in memory at a time.
     * Returns false when the file is gone or turns out unreadable; failures of [action] propagate.
     */
    suspend fun forEachOriginal(entry: GlobalStyleHistoryEntry, action: suspend (Original) -> Unit): Boolean {
        val (reader, _) = openAtIcons(entry) ?: return false
        reader.use {
            if (!parsed { reader.beginArray() }) return false
            while (true) {
                var original: Original? = null
                if (!parsed { if (reader.hasNext()) original = reader.readOriginal() }) return false
                action(original ?: return true)
            }
        }
    }

    fun delete(entryId: Long) {
        file(entryId).delete()
    }

    /** Drops the originals of entries that were trimmed, deleted or lost with their profile. */
    fun prune(entryIds: Set<Long>) {
        directory.listFiles { file -> file.name.endsWith(SUFFIX) && !file.name.startsWith(PENDING_PREFIX) }?.forEach { file ->
            if (file.name.removeSuffix(SUFFIX).toLongOrNull() !in entryIds) file.delete()
        }
    }

    private fun file(entryId: Long) = File(directory, "$entryId$SUFFIX")

    // Reads the header and returns the reader positioned at the icons array; the caller closes it.
    private fun openAtIcons(entry: GlobalStyleHistoryEntry): Pair<JsonReader, Header>? {
        val file = file(entry.id)
        if (!file.isFile) return null
        val reader = try {
            JsonReader(InputStreamReader(GZIPInputStream(file.inputStream().buffered()), Charsets.UTF_8))
        } catch (error: IOException) {
            return null
        }
        var header: Header? = null
        val positioned = parsed {
            var version = 0
            var profileId = -1L
            var appliedAt = -1L
            var bakedStyle: String? = null
            reader.beginObject()
            while (header == null && reader.hasNext()) {
                when (reader.nextName()) {
                    "version" -> version = reader.nextInt()
                    "profileId" -> profileId = reader.nextLong()
                    "appliedAt" -> appliedAt = reader.nextLong()
                    "bakedStyle" -> bakedStyle = reader.nextString()
                    "icons" -> {
                        val style = bakedStyle ?: return@parsed
                        if (version != VERSION || profileId != entry.profileId || appliedAt != entry.appliedAt) return@parsed
                        header = Header(profileId, appliedAt, style)
                    }
                    else -> reader.skipValue()
                }
            }
        }
        val found = header
        if (!positioned || found == null) {
            reader.close()
            return null
        }
        return reader to found
    }

    // Only reading the file may fail quietly; errors of the code around it must still propagate.
    private inline fun parsed(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: IOException) {
        false
    } catch (error: IllegalStateException) {
        false
    }

    private fun JsonReader.readOriginal(): Original {
        var packageName = ""
        var activityName = ""
        var base = ""
        var baseIsXml = false
        var baseIsAdaptive = false
        var bakedFingerprint = ""
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "packageName" -> packageName = nextString()
                "activityName" -> activityName = nextString()
                "base" -> base = nextString()
                "baseIsXml" -> baseIsXml = nextBoolean()
                "baseIsAdaptive" -> baseIsAdaptive = nextBoolean()
                "bakedFingerprint" -> bakedFingerprint = nextString()
                else -> skipValue()
            }
        }
        endObject()
        return Original(packageName, activityName, base, baseIsXml, baseIsAdaptive, bakedFingerprint)
    }

    companion object {
        private const val VERSION = 1
        private const val SUFFIX = ".json.gz"
        private const val PENDING_PREFIX = "pending-"

        /** Fingerprint of a stored base payload, as a row reads it back. */
        fun fingerprint(storedBase: String): String =
            MessageDigest.getInstance("SHA-256").digest(storedBase.toByteArray()).toLowerHex()
    }
}
