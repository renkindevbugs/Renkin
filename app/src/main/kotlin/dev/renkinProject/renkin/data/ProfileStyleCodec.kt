package dev.renkinProject.renkin.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.json.JSONObject
import java.io.StringReader

internal object ProfileStyleCodec {
    const val VERSION = 1
    const val MAX_DOCUMENT_CHARS = 256 * 1024
    const val MAX_DOCUMENT_BYTES = 256 * 1024
    private const val MAX_FIELDS = 256
    private val fields = ProfileStyleSchema.fields.associateBy { it.path }
    private val groups = fields.keys.flatMap { path ->
        val parts = path.split('.')
        (1 until parts.size).map { parts.take(it).joinToString(".") }
    }.toSet()

    fun encode(style: ProfileStyle): String {
        val root = JSONObject().put("version", VERSION)
        ProfileStyleSchema.groups.forEach { root.put(it, JSONObject()) }
        var stringChars = 0L
        var stringBytes = 0L
        for (field in ProfileStyleSchema.fields) {
            val value = field.jsonValue(style) ?: continue
            if (value is String) {
                stringChars += value.length
                require(stringChars <= MAX_DOCUMENT_CHARS) { "Profile style is too large" }
                value.requireProfileUnicode()
                stringBytes += value.toByteArray(Charsets.UTF_8).size
                require(stringBytes <= MAX_DOCUMENT_BYTES) { "Profile style is too large" }
            }
            val path = field.path.split('.')
            var target = root
            for (group in path.dropLast(1)) {
                target = target.optJSONObject(group) ?: JSONObject().also { target.put(group, it) }
            }
            target.put(path.last(), value)
        }
        return root.toString().also(::requireSize)
    }

    fun decode(encoded: String): ProfileStyle {
        requireSize(encoded)
        try {
            return JsonReader(StringReader(encoded)).use { reader ->
                reader.isLenient = false
                val target = mutablePreferencesOf()
                val parser = Parser(reader, target)
                val rootFields = parser.group("")
                require(rootFields == ProfileStyleSchema.groups + "version") { "Incomplete profile style document" }
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing profile style content" }
                ProfileStylePreferences.capture(target)
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid profile style document", error)
        }
    }

    private fun requireSize(encoded: String) {
        require(encoded.length <= MAX_DOCUMENT_CHARS) { "Profile style is too large" }
        encoded.requireProfileUnicode()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Profile style is too large" }
    }

    private class Parser(private val reader: JsonReader, private val target: MutablePreferences) {
        private var count = 0

        fun group(prefix: String): Set<String> {
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected profile style group" }
            reader.beginObject()
            val seen = mutableSetOf<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require('.' !in name) { "Invalid profile style field name" }
                require(seen.add(name)) { "Duplicate profile style field" }
                require(++count <= MAX_FIELDS) { "Too many profile style fields" }
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                when {
                    prefix.isEmpty() && name == "version" ->
                        require(reader.profileInt(path) == VERSION) { "Unsupported profile style version" }
                    path in fields -> fields.getValue(path).readJson(reader, target)
                    path in groups -> group(path)
                    else -> throw IllegalArgumentException("Unknown profile style field")
                }
            }
            reader.endObject()
            return seen
        }
    }
}

internal fun String.requireProfileUnicode() {
    require(Charsets.UTF_8.newEncoder().canEncode(this)) { "Invalid profile style Unicode" }
}
