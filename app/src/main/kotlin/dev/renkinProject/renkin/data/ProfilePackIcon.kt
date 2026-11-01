package dev.renkinProject.renkin.data

import android.content.Context
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import dev.renkinProject.renkin.icon.creator.StoredModifierArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

internal data class ProfilePackIcon(
    val icon: IconPackDrawable,
    val sourcePackName: String?,
    val sourceUrl: String?,
    val modifierRecipe: String?,
    val modifierInput: IconModifierInput?
)

internal class ProfilePackIconCodec(context: Context) {
    private val inputs = IconModifierInputStore(context.applicationContext)

    suspend fun encode(
        icon: IconPackDrawable?,
        sourcePackName: String? = null,
        sourceUrl: String? = null,
        modifierRecipe: String? = null,
        modifierInput: IconModifierInput? = null
    ): String = withContext(Dispatchers.Default) {
        if (icon == null) return@withContext ""
        val value = JSONObject().put("version", VERSION).put("icon", artwork(icon))
            .put("sourcePackName", sourcePackName.orEmpty()).put("sourceUrl", sourceUrl.orEmpty())
            .put("modifierRecipe", StoredIconModifierRecipe.normalize(modifierRecipe).orEmpty())
        modifierInput?.let { state ->
            val input = inputs.read(state)?.let { inputs.decode(it) }
                ?: error("The original modifier input is no longer available")
            val retained = artwork(input)
            val metadata = StoredIconModifierInput.encode(state, retained.descriptor())
                ?: error("The original modifier input cannot be retained")
            value.put("modifierInput", metadata).put("inputArtwork", retained)
        }
        normalize(value.toString())
    }

    suspend fun decode(encoded: String): ProfilePackIcon? = withContext(Dispatchers.Default) {
        if (encoded.isEmpty()) return@withContext null
        val value = JSONObject(normalize(encoded))
        val icon = decodeArtwork(value.getJSONObject("icon"))
        val input = if (value.has("inputArtwork")) {
            val retained = StoredIconModifierInput.readState(value.getString("modifierInput"))
                ?: error("Invalid profile icon modifier input")
            val artwork = value.getJSONObject("inputArtwork")
            require(retained.storedInput == artwork.descriptor()) { "Profile icon input does not match its metadata" }
            IconModifierInput(retained.options, retained.containsBakedModifierEdits, retained.origin,
                liveInput = decodeArtwork(artwork), pipeline = retained.pipeline)
        } else null
        ProfilePackIcon(icon, value.optString("sourcePackName").ifEmpty { null },
            value.optString("sourceUrl").ifEmpty { null },
            StoredIconModifierRecipe.normalize(value.optString("modifierRecipe")), input)
    }

    private suspend fun decodeArtwork(value: JSONObject): IconPackDrawable =
        inputs.decode(IconModifierInputStore.ReadInput.Stored(
            value.getString("payload").toByteArray(Charsets.UTF_8), value.descriptor()
        )) ?: error("Profile icon artwork could not be decoded")

    private fun artwork(icon: IconPackDrawable): JSONObject = JSONObject()
        .put("payload", icon.toDbString()).put("xml", icon !is BitmapIconDrawable)
        .put("adaptive", icon.isAdaptiveIcon())
        .put("previewScale", (icon as? BitmapIconDrawable)?.previewScale ?: 1f)

    private fun JSONObject.descriptor(): StoredModifierArtwork = StoredModifierArtwork(
        MessageDigest.getInstance("SHA-256").digest(getString("payload").toByteArray(Charsets.UTF_8)).toLowerHex(),
        getBoolean("xml"), getBoolean("adaptive"), getDouble("previewScale").toFloat()
    )

    companion object {
        private const val VERSION = 1
        // Profile reads use ordinary Room cursors; leave room for the remaining row values.
        const val MAX_DOCUMENT_CHARS = 768 * 1024

        fun normalize(encoded: String): String {
            if (encoded.isEmpty()) return ""
            require(encoded.length <= MAX_DOCUMENT_CHARS && encoded.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_CHARS) {
                "The profile icon is too large"
            }
            val value = JSONObject(encoded)
            require(value.getInt("version") == VERSION) { "Unsupported profile icon format" }
            validateArtwork(value.getJSONObject("icon"))
            for (name in listOf("sourcePackName", "sourceUrl")) require(value.optString(name).length <= 4096)
            require(value.optString("modifierRecipe").length <= StoredIconModifierInput.MAX_DOCUMENT_CHARS)
            require(value.has("inputArtwork") == value.has("modifierInput")) { "Incomplete profile icon input" }
            if (value.has("inputArtwork")) {
                validateArtwork(value.getJSONObject("inputArtwork"))
                require(StoredIconModifierInput.normalize(value.getString("modifierInput")) != null)
            }
            return encoded
        }

        fun sourcePackName(encoded: String): String? = if (encoded.isEmpty()) null else
            JSONObject(normalize(encoded)).optString("sourcePackName").ifEmpty { null }

        private fun validateArtwork(value: JSONObject) {
            val payload = value.getString("payload")
            require(payload.isNotEmpty() && payload.all { it.code < 128 }) { "Invalid profile icon artwork" }
            value.getBoolean("xml"); value.getBoolean("adaptive")
            val scale = value.getDouble("previewScale").toFloat()
            require(scale.isFinite() && scale in 0.1f..10f)
        }

    }
}
