package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.util.Log
import org.json.JSONObject

internal object StoredIconModifierInput {
    private const val VERSION = 2
    internal const val MAX_DOCUMENT_CHARS = 64 * 1024
    private const val MAX_TEXT_CHARS = 8192
    private const val MAX_STYLE_CHARS = 16384

    private data class Document(
        val hash: String,
        val isXml: Boolean,
        val isAdaptive: Boolean,
        val options: GenerationOptions,
        val containsBakedModifierEdits: Boolean,
        val origin: ModifierInputOrigin
    )

    fun encode(state: IconModifierInput, artwork: StoredModifierArtwork): String? {
        if (!supportsOptions(state.options) || !validHash(artwork.hash)) return null
        return try {
            val document = Document(
                artwork.hash, artwork.isXml, artwork.isAdaptive,
                state.options, state.containsBakedModifierEdits, state.origin
            )
            writeBounded(document)
        } catch (error: Exception) {
            Log.error("StoredIconModifierInput", "Cannot retain optional modifier input", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.error("StoredIconModifierInput", "Optional modifier input exceeds available memory", error)
            null
        }
    }

    fun normalize(encoded: String?): String? = try {
        read(encoded)?.let(::writeBounded)
    } catch (error: OutOfMemoryError) {
        Log.error("StoredIconModifierInput", "Cannot normalize optional modifier input within available memory", error)
        null
    }

    fun readState(encoded: String?): IconModifierInput? {
        val document = read(encoded) ?: return null
        return IconModifierInput(
            document.options, document.containsBakedModifierEdits, document.origin,
            storedInput = StoredModifierArtwork(document.hash, document.isXml, document.isAdaptive)
        )
    }

    fun artwork(encoded: String?): StoredModifierArtwork? = read(encoded)?.let {
        StoredModifierArtwork(it.hash, it.isXml, it.isAdaptive)
    }

    fun validHash(hash: String): Boolean = hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }

    fun supportsOptions(options: GenerationOptions): Boolean = runCatching {
        val recipe = StoredIconModifierRecipe.fromRenderedOptions(options) ?: return@runCatching false
        val serialized = writeOptions(options, recipe)
        val encoded = serialized.toString()
        // Leave room for the fixed document wrapper before retaining a live source.
        encoded.length + 512 <= MAX_DOCUMENT_CHARS &&
            encoded.toByteArray(Charsets.UTF_8).size + 512 <= MAX_DOCUMENT_CHARS && readOptions(serialized) == options
    }.getOrDefault(false)

    private fun read(encoded: String?): Document? {
        if (encoded.isNullOrEmpty() || encoded.length > MAX_DOCUMENT_CHARS) return null
        return try {
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_CHARS)
            val json = JSONObject(encoded)
            require(json.getInt("version") == VERSION)
            val hash = json.getString("artworkHash")
            require(validHash(hash))
            Document(
                hash, json.getBoolean("xml"), json.getBoolean("adaptive"),
                readOptions(json.getJSONObject("options")), json.getBoolean("bakedModifiers"),
                enumValueOf<ModifierInputOrigin>(json.getString("origin"))
            )
        } catch (_: Exception) {
            null
        } catch (error: OutOfMemoryError) {
            Log.error("StoredIconModifierInput", "Cannot read optional modifier input within available memory", error)
            null
        }
    }

    private fun writeBounded(document: Document): String? = write(document).takeIf {
        it.length <= MAX_DOCUMENT_CHARS && it.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_CHARS
    }

    private fun write(document: Document): String = JSONObject()
        .put("version", VERSION)
        .put("artworkHash", document.hash)
        .put("xml", document.isXml)
        .put("adaptive", document.isAdaptive)
        .put("bakedModifiers", document.containsBakedModifierEdits)
        .put("origin", document.origin.name)
        .put("options", writeOptions(document.options, ModifierRecipeCodec.encode(document.options.modifiers)))
        .toString()

    private fun writeOptions(options: GenerationOptions, recipe: String): JSONObject {
        val source = options.source
        val export = options.export
        return JSONObject()
            .put("source", JSONObject()
                .put("primary", source.primarySource.name)
                .put("primaryText", source.primaryTextType.name)
                .put("primaryPack", source.primaryIconPack)
                .put("secondary", source.secondarySource.name)
                .put("secondaryEdit", source.secondaryImageEdit.name)
                .put("secondaryText", source.secondaryTextType.name)
                .put("secondaryPack", source.secondaryIconPack)
                .put("text", source.textCustom)
                .put("textCase", source.textCase.name)
                .put("font", source.textFontPath)
                .put("fallback", source.fallbackSource.name)
                .put("variant", source.applicationIconVariant.name)
                .put("invert", source.invertMonochrome)
                .put("fullIcon", source.useFullApplicationIcon))
            .put("export", JSONObject()
                .put("vector", export.vector)
                .put("materialYou", export.materialYou)
                .put("themed", export.themed)
                .put("background", export.background.color)
                .put("backgroundStyle", export.background.style?.let(::encodeColorizerStyle) ?: JSONObject.NULL)
                .put("foregroundStyle", export.foregroundStyle?.let(::encodeColorizerStyle) ?: JSONObject.NULL))
            .put("recipe", recipe)
            .put("override", options.override)
    }

    private fun readOptions(json: JSONObject): GenerationOptions {
        val source = json.getJSONObject("source")
        val export = json.getJSONObject("export")
        val modifiers = ModifierRecipeCodec.decode(json.getString("recipe")) ?: error("Invalid input recipe")
        return GenerationOptions(
            source = IconSourceOptions(
                primarySource = enumValueOf<Source>(source.getString("primary")),
                primaryTextType = enumValueOf<TextType>(source.getString("primaryText")),
                primaryIconPack = source.text("primaryPack"),
                secondarySource = enumValueOf<Source>(source.getString("secondary")),
                secondaryImageEdit = enumValueOf<ImageEdit>(source.getString("secondaryEdit")),
                secondaryTextType = enumValueOf<TextType>(source.getString("secondaryText")),
                secondaryIconPack = source.text("secondaryPack"),
                textCustom = source.text("text"),
                textCase = enumValueOf<TextCase>(source.getString("textCase")),
                textFontPath = source.text("font"),
                fallbackSource = enumValueOf<FallbackSource>(source.getString("fallback")),
                applicationIconVariant = enumValueOf<ApplicationIconVariant>(source.getString("variant")),
                invertMonochrome = source.getBoolean("invert"),
                useFullApplicationIcon = source.getBoolean("fullIcon")
            ),
            modifiers = modifiers,
            export = IconExportOptions(
                vector = export.getBoolean("vector"),
                materialYou = export.getBoolean("materialYou"),
                themed = export.getBoolean("themed"),
                background = ColorFill(export.getInt("background"), export.style("backgroundStyle")),
                foregroundStyle = export.style("foregroundStyle")
            ),
            override = json.getBoolean("override")
        )
    }

    private fun JSONObject.text(key: String): String = getString(key).also {
        require(it.length <= MAX_TEXT_CHARS)
    }

    private fun JSONObject.style(key: String): ColorizerStyle? {
        if (isNull(key)) return null
        val encoded = getString(key)
        require(encoded.length <= MAX_STYLE_CHARS)
        val style = decodeColorizerStyle(encoded) ?: error("Invalid input colour style")
        require(style.gradientAngle.isFinite() && style.gradientStops.size <= 64 &&
            style.gradientPositions.size <= 65 && style.gradientPositions.all { it.isFinite() && it in 0f..1f })
        require(style.gradientPositions.isEmpty() || style.gradientPositions.size == style.allGradientColors.size)
        return style
    }
}
