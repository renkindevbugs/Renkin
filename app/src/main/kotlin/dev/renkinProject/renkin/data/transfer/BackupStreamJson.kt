package dev.renkinProject.renkin.data.transfer

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.watch.AppComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.io.Writer

internal suspend fun readBackupData(input: Reader, formatVersion: Int): BackupData =
    readBackupDocument(input, formatVersion, BackupJsonNode.FULL)

/** Metadata for inspection only; never use this model for an import or export. */
internal suspend fun readBackupInspectionData(input: Reader, formatVersion: Int): BackupData =
    readBackupDocument(input, formatVersion, BackupJsonNode.ROOT)

internal interface BackupJsonRowSink {
    suspend fun icon(profileIndex: Int, value: JSONObject): JSONObject
    fun endProfile(profileIndex: Int)
}

internal suspend fun readBackupStagingMetadata(input: Reader, formatVersion: Int, rows: BackupJsonRowSink): BackupData =
    readBackupDocument(input, formatVersion, BackupJsonNode.ROOT, rows)

private suspend fun readBackupDocument(
    input: Reader, formatVersion: Int, node: BackupJsonNode, rows: BackupJsonRowSink? = null
): BackupData = withBackupJsonReader(input) { reader ->
    val root = readJsonValue(reader, 0, node, rows = rows) as? JSONObject ?: throw IOException("Backup data must be an object")
    if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing backup JSON content")
    BackupCodec.decodeJson(root, formatVersion)
}

internal suspend fun readBackupIconRows(input: Reader, profileId: Long, consume: suspend (DbApplication) -> Unit) =
    withBackupJsonReader(input) { reader ->
        reader.beginArray()
        while (reader.hasNext()) {
            val value = readJsonValue(reader, 1) as? JSONObject ?: throw IOException("Invalid staged icon")
            consume(BackupCodec.decodeIcon(value, profileId))
        }
        reader.endArray()
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing staged icon content")
    }

internal suspend fun writeBackupIcon(writer: JsonWriter, icon: DbApplication) =
    writeJsonValue(writer, BackupCodec.encodeIcon(icon))

private suspend fun <T> withBackupJsonReader(input: Reader, operation: suspend (JsonReader) -> T): T {
    val context = currentCoroutineContext()
    val cancellable = object : Reader() {
        override fun read(buffer: CharArray, offset: Int, count: Int): Int {
            context.ensureActive()
            return input.read(buffer, offset, count)
        }
        override fun close() = input.close()
    }
    return JsonReader(cancellable).use { operation(it) }
}

private suspend fun readJsonValue(
    reader: JsonReader, depth: Int, node: BackupJsonNode = BackupJsonNode.FULL, retain: Boolean = true,
    rows: BackupJsonRowSink? = null, profileIndex: Int = 0
): Any {
    currentCoroutineContext().ensureActive()
    if (depth > 64) throw IOException("Backup JSON is nested too deeply")
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val value = if (retain) JSONObject() else null
            val names = if (!retain || node != BackupJsonNode.FULL) mutableSetOf<String>() else null
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (names?.add(name) == false || (names == null && value!!.has(name))) {
                    throw IOException("Duplicate backup JSON field")
                }
                val keep = retain && node.retains(name)
                val child = readJsonValue(reader, depth + 1, node.child(name), keep, rows, profileIndex)
                if (keep) {
                    value!!.put(name, child)
                    if (rows == null && node == BackupJsonNode.ICON && (name == "drawable" || name == "baseDrawable")) {
                        // Preserve the required field/coercion check, then drop this one value.
                        if (name == "drawable") value.getString(name)
                        value.put(name, "")
                    }
                }
            }
            reader.endObject()
            // Most saved icons have an unchanged base. Share their identical strings while
            // reading each icon instead of keeping both payloads until the whole JSON is parsed.
            value?.let {
                val drawable = it.opt("drawable")
                if (drawable is String && drawable == it.opt("baseDrawable")) it.put("baseDrawable", drawable)
                node.finish(it)
                if (node == BackupJsonNode.PROFILE) rows?.endProfile(profileIndex)
                if (node == BackupJsonNode.ICON && rows != null) rows.icon(profileIndex, it) else it
            } ?: JSONObject.NULL
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val value = if (retain) JSONArray() else null
            var index = 0
            while (reader.hasNext()) {
                val child = readJsonValue(reader, depth + 1, node.element(), retain, rows,
                    if (node == BackupJsonNode.PROFILES) index else profileIndex)
                value?.put(child)
                index++
            }
            reader.endArray()
            value ?: JSONObject.NULL
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }
        JsonToken.NUMBER -> {
            val number = reader.nextString()
            val integer = number.toLongOrNull()
            if (integer != null) {
                if (integer in Int.MIN_VALUE..Int.MAX_VALUE) integer.toInt() else integer
            } else number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IOException("Invalid backup number")
        }
        else -> throw IOException("Invalid backup JSON value")
    }
}

internal suspend fun writeBackupData(
    data: BackupData, output: Writer, formatVersion: Int
) {
    val writer = JsonWriter(output.buffered())
    writeJsonValue(writer, BackupCodec.encodeJson(data, formatVersion))
    writer.flush()
}

internal suspend fun writeBackupExport(
    metadata: BackupData, output: Writer, formatVersion: Int,
    visit: suspend (Long, suspend (DbApplication) -> Unit) -> Unit
) {
    require(metadata.profiles.all { it.icons.isEmpty() }) { "Export metadata contains artwork rows" }
    val root = BackupCodec.encodeJson(metadata, formatVersion)
    val writer = JsonWriter(output.buffered())
    writer.beginObject()
    for (name in root.keys()) {
        writer.name(name)
        if (name != "profiles") {
            writeJsonValue(writer, root.get(name))
            continue
        }
        writer.beginArray()
        val profiles = root.getJSONArray(name)
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            writer.beginObject()
            for (field in profile.keys()) {
                writer.name(field)
                if (field != "icons") {
                    writeJsonValue(writer, profile.get(field))
                    continue
                }
                val components = mutableSetOf<AppComponent>()
                val profileId = metadata.profiles[index].profile.id
                writer.beginArray()
                visit(profileId) { icon ->
                    BackupValidator.validateIcon(icon, profileId)
                    if (!components.add(AppComponent(icon.packageName, icon.activityName))) {
                        throw IOException("Duplicate profile icon")
                    }
                    writeJsonValue(writer, BackupCodec.encodeIcon(icon))
                }
                writer.endArray()
            }
            writer.endObject()
        }
        writer.endArray()
    }
    writer.endObject()
    writer.flush()
}

private suspend fun writeJsonValue(writer: JsonWriter, value: Any?) {
    currentCoroutineContext().ensureActive()
    when (value) {
        null, JSONObject.NULL -> writer.nullValue()
        is JSONObject -> {
            writer.beginObject()
            for (name in value.keys()) {
                writer.name(name)
                writeJsonValue(writer, value.get(name))
            }
            writer.endObject()
        }
        is JSONArray -> {
            writer.beginArray()
            for (index in 0 until value.length()) writeJsonValue(writer, value.get(index))
            writer.endArray()
        }
        is String -> writer.value(value)
        is Boolean -> writer.value(value)
        is Number -> writer.value(value)
        else -> throw IOException("Unsupported backup JSON value")
    }
}
