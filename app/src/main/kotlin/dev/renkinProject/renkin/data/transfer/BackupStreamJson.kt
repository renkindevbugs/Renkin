package dev.renkinProject.renkin.data.transfer

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.io.Writer

internal suspend fun readBackupData(input: Reader, formatVersion: Int): BackupData {
    val context = currentCoroutineContext()
    val cancellable = object : Reader() {
        override fun read(buffer: CharArray, offset: Int, count: Int): Int {
            context.ensureActive()
            return input.read(buffer, offset, count)
        }
        override fun close() = input.close()
    }
    return JsonReader(cancellable).use { reader ->
        val root = readJsonValue(reader, 0) as? JSONObject ?: throw IOException("Backup data must be an object")
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing backup JSON content")
        BackupCodec.decodeJson(root, formatVersion)
    }
}

private suspend fun readJsonValue(reader: JsonReader, depth: Int): Any {
    currentCoroutineContext().ensureActive()
    if (depth > 64) throw IOException("Backup JSON is nested too deeply")
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val value = JSONObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (value.has(name)) throw IOException("Duplicate backup JSON field")
                value.put(name, readJsonValue(reader, depth + 1))
            }
            reader.endObject()
            // Most saved icons have an unchanged base. Share their identical strings while
            // reading each icon instead of keeping both payloads until the whole JSON is parsed.
            val drawable = value.opt("drawable")
            if (drawable is String && drawable == value.opt("baseDrawable")) value.put("baseDrawable", drawable)
            value
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val value = JSONArray()
            while (reader.hasNext()) value.put(readJsonValue(reader, depth + 1))
            reader.endArray()
            value
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
