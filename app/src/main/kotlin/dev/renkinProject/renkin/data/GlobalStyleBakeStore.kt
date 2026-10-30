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

    /** Written before the bake touches any icon, so a failed write leaves nothing to undo. */
    fun writePending(header: Header, originals: List<Original>): File {
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

    fun header(entry: GlobalStyleHistoryEntry): Header? = read(entry) { _, header -> header }

    fun originals(entry: GlobalStyleHistoryEntry): List<Original>? = read(entry) { reader, _ ->
        buildList {
            reader.beginArray()
            while (reader.hasNext()) add(reader.readOriginal())
            reader.endArray()
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

    // Reads the header, then hands the reader positioned at the icons array to [body].
    private fun <T> read(entry: GlobalStyleHistoryEntry, body: (JsonReader, Header) -> T): T? {
        val file = file(entry.id)
        if (!file.isFile) return null
        return try {
            JsonReader(InputStreamReader(GZIPInputStream(file.inputStream().buffered()), Charsets.UTF_8)).use { reader ->
                var version = 0
                var profileId = -1L
                var appliedAt = -1L
                var bakedStyle: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "version" -> version = reader.nextInt()
                        "profileId" -> profileId = reader.nextLong()
                        "appliedAt" -> appliedAt = reader.nextLong()
                        "bakedStyle" -> bakedStyle = reader.nextString()
                        "icons" -> {
                            val header = Header(profileId, appliedAt, bakedStyle ?: return null)
                            if (version != VERSION || profileId != entry.profileId || appliedAt != entry.appliedAt) return null
                            return body(reader, header)
                        }
                        else -> reader.skipValue()
                    }
                }
                null
            }
        } catch (error: IOException) {
            null
        } catch (error: IllegalStateException) {
            null
        }
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
