package dev.renkinProject.renkin.data.editor

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/** Only the storage envelope is decoded here; editor controls/artwork have their own codecs. */
internal object EditorDraftCodec {
    const val VERSION = 1
    const val MAX_METADATA_BYTES = 32 * 1024
    const val MAX_ARTIFACT_BYTES = 32L * 1024 * 1024
    const val MAX_CONTROLS_BYTES = 2L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_ARTIFACTS = 12
    private val identity = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val fingerprint = Regex("[0-9a-f]{64}")
    private val artifactName = Regex("[a-z][a-z0-9-]{0,47}")

    fun encode(catalog: EditorDraftCatalog): String {
        validate(catalog)
        val draft = catalog.draft?.let { record ->
            JSONObject().put("id", record.id).put("savedAt", record.savedAt)
                .put("owner", JSONObject().put("profileId", record.owner.profileId)
                    .put("packageName", record.owner.packageName).put("activityName", record.owner.activityName)
                    .put("profileFingerprint", record.owner.profileFingerprint)
                    .put("originalIconFingerprint", record.owner.originalIconFingerprint))
                .put("artifacts", JSONArray().apply {
                    record.artifacts.forEach { artifact ->
                        put(JSONObject().put("name", artifact.name).put("bytes", artifact.bytes)
                            .put("sha256", artifact.sha256))
                    }
                })
        } ?: JSONObject.NULL
        return JSONObject().put("version", VERSION).put("storageGeneration", catalog.storageGeneration)
            .put("draft", draft).toString().also(::requireDocumentSize)
    }

    fun decode(encoded: String): EditorDraftCatalog {
        requireDocumentSize(encoded)
        return JsonReader(StringReader(encoded)).use { reader ->
            reader.isLenient = false
            var generation = ""
            var draft: EditorDraftRecord? = null
            reader.fields(setOf("version", "storageGeneration", "draft")) { name ->
                when (name) {
                    "version" -> require(reader.integer() == VERSION.toLong()) { "Unsupported editor draft version" }
                    "storageGeneration" -> generation = reader.string()
                    "draft" -> draft = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else reader.record()
                }
            }
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing editor draft content" }
            EditorDraftCatalog(generation, draft).also(::validate)
        }
    }

    fun validate(catalog: EditorDraftCatalog) {
        require(identity.matches(catalog.storageGeneration)) { "Invalid draft storage generation" }
        val draft = catalog.draft ?: return
        require(validId(draft.id) && draft.savedAt >= 0) { "Invalid editor draft identity" }
        require(draft.owner.profileId > 0) { "Invalid editor draft profile" }
        requireComponent(draft.owner.packageName, 255)
        requireComponent(draft.owner.activityName, 1024)
        require(fingerprint.matches(draft.owner.profileFingerprint) &&
            fingerprint.matches(draft.owner.originalIconFingerprint)) { "Invalid editor draft baseline" }
        require(draft.artifacts.size in 1..MAX_ARTIFACTS) { "Invalid editor draft artifact count" }
        require(draft.artifacts.map { it.name }.toSet().size == draft.artifacts.size) { "Duplicate draft artifact" }
        require(draft.artifacts.any { it.name == "controls" }) { "Editor draft controls are missing" }
        draft.artifacts.forEach { artifact ->
            require(artifactName.matches(artifact.name)) { "Invalid editor draft artifact name" }
            require(fingerprint.matches(artifact.sha256)) { "Invalid editor draft checksum" }
            val limit = if (artifact.name == "controls") MAX_CONTROLS_BYTES else MAX_ARTIFACT_BYTES
            require(artifact.bytes in 1..limit) { "Editor draft artifact is too large" }
        }
        require(draft.artifacts.sumOf { it.bytes } <= MAX_TOTAL_BYTES) { "Editor draft is too large" }
    }

    fun validId(value: String): Boolean = identity.matches(value)

    fun ownedArtifactName(value: String): Boolean {
        if (!value.endsWith(".blob") || value.length < 43) return false
        return validId(value.take(36)) && value[36] == '-' &&
            artifactName.matches(value.substring(37, value.length - 5))
    }

    private fun requireDocumentSize(encoded: String) {
        require(encoded.length <= MAX_METADATA_BYTES && Charsets.UTF_8.newEncoder().canEncode(encoded)) {
            "Invalid editor draft metadata size or Unicode"
        }
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "Editor draft metadata is too large" }
    }

    private fun requireComponent(value: String, limit: Int) {
        require(value.isNotBlank() && value.length <= limit && value.none { it.isISOControl() } &&
            Charsets.UTF_8.newEncoder().canEncode(value)) { "Invalid editor draft component" }
    }

    private fun JsonReader.record(): EditorDraftRecord {
        var id = ""
        var savedAt = -1L
        var draftOwner: EditorDraftOwner? = null
        val artifacts = mutableListOf<EditorDraftArtifact>()
        fields(setOf("id", "savedAt", "owner", "artifacts")) { name ->
            when (name) {
                "id" -> id = string()
                "savedAt" -> savedAt = integer()
                "owner" -> draftOwner = owner()
                "artifacts" -> {
                    require(peek() == JsonToken.BEGIN_ARRAY) { "Expected editor draft artifacts" }
                    beginArray()
                    while (hasNext()) {
                        require(artifacts.size < MAX_ARTIFACTS) { "Too many editor draft artifacts" }
                        artifacts += artifact()
                    }
                    endArray()
                }
            }
        }
        return EditorDraftRecord(id, savedAt, requireNotNull(draftOwner), artifacts.toList())
    }

    private fun JsonReader.owner(): EditorDraftOwner {
        var profileId = 0L
        var packageName = ""
        var activityName = ""
        var profileFingerprint = ""
        var originalIconFingerprint = ""
        fields(setOf("profileId", "packageName", "activityName", "profileFingerprint", "originalIconFingerprint")) { name ->
            when (name) {
                "profileId" -> profileId = integer()
                "packageName" -> packageName = string()
                "activityName" -> activityName = string()
                "profileFingerprint" -> profileFingerprint = string()
                "originalIconFingerprint" -> originalIconFingerprint = string()
            }
        }
        return EditorDraftOwner(profileId, packageName, activityName, profileFingerprint, originalIconFingerprint)
    }

    private fun JsonReader.artifact(): EditorDraftArtifact {
        var name = ""
        var bytes = 0L
        var sha256 = ""
        fields(setOf("name", "bytes", "sha256")) { field ->
            when (field) {
                "name" -> name = string()
                "bytes" -> bytes = integer()
                "sha256" -> sha256 = string()
            }
        }
        return EditorDraftArtifact(name, bytes, sha256)
    }

    private inline fun JsonReader.fields(expected: Set<String>, read: (String) -> Unit) {
        require(peek() == JsonToken.BEGIN_OBJECT) { "Expected editor draft object" }
        beginObject()
        val seen = mutableSetOf<String>()
        while (hasNext()) {
            val name = nextName()
            require(name in expected && seen.add(name)) { "Unknown or duplicate editor draft field" }
            read(name)
        }
        endObject()
        require(seen == expected) { "Incomplete editor draft metadata" }
    }

    private fun JsonReader.string(): String {
        require(peek() == JsonToken.STRING) { "Expected editor draft string" }
        return nextString().also {
            require(Charsets.UTF_8.newEncoder().canEncode(it)) { "Invalid editor draft Unicode" }
        }
    }

    private fun JsonReader.integer(): Long {
        require(peek() == JsonToken.NUMBER) { "Expected editor draft integer" }
        return requireNotNull(nextString().toLongOrNull()) { "Invalid editor draft integer" }
    }
}
