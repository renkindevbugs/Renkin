package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class StoredIconModifierInputTest {
    @Test fun v2DocumentsUpgradeToTheSharedPipelineWithoutLosingSettings() {
        val original = state()
        val legacy = JSONObject(checkNotNull(StoredIconModifierInput.encode(original, artwork)))
            .put("version", 2).apply { remove("pipeline"); remove("previewScale") }.toString()
        val restored = checkNotNull(StoredIconModifierInput.readState(legacy))
        assertEquals(original.options, restored.options)
        assertEquals(ModifierInputPipeline.SHARED, restored.pipeline)
        assertEquals(1f, restored.storedInput!!.previewScale)
        val normalized = checkNotNull(StoredIconModifierInput.normalize(legacy))
        assertEquals(3, JSONObject(normalized).getInt("version"))
        assertEquals(artwork, StoredIconModifierInput.artwork(normalized))
    }

    @Test fun freshPipelinesRoundTripWithTheirArtworkRepresentation() {
        for (pipeline in ModifierInputPipeline.entries.filter { it != ModifierInputPipeline.SHARED }) {
            val options = modifierInputTestOptions().withSource {
                copy(primarySource = if (pipeline == ModifierInputPipeline.ADJUSTMENTS_ONLY) Source.APPLICATION_NAME else Source.APPLICATION_ICON)
            }
            val source = IconModifierInput(options, false, ModifierInputOrigin.FRESH_SOURCE, pipeline = pipeline)
            val representation = when (pipeline) {
                ModifierInputPipeline.ADJUSTMENTS_ONLY -> artwork.copy(isAdaptive = false)
                ModifierInputPipeline.BITMAP_IMAGE_EDIT -> artwork.copy(isXml = false, isAdaptive = false)
                else -> artwork.copy(isXml = false, previewScale = 1.5f)
            }
            val encoded = checkNotNull(StoredIconModifierInput.encode(source, representation))
            val restored = checkNotNull(StoredIconModifierInput.readState(encoded))
            assertEquals(pipeline, restored.pipeline)
            assertEquals(source.options, restored.options)
            assertEquals(representation, restored.storedInput)
        }
    }

    @Test fun incompatibleStageFlagsAreRejectedOnReadAndWrite() {
        val source = IconModifierInput(modifierInputTestOptions(), false, ModifierInputOrigin.FRESH_SOURCE,
            pipeline = ModifierInputPipeline.BITMAP_IMAGE_EDIT)
        val bitmap = artwork.copy(isXml = false, isAdaptive = false)
        val encoded = checkNotNull(StoredIconModifierInput.encode(source, bitmap))
        for (invalid in listOf(bitmap.copy(isXml = true), bitmap.copy(isAdaptive = true), bitmap.copy(previewScale = 1.5f))) {
            assertNull(StoredIconModifierInput.encode(source, invalid))
        }
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("xml", true) }, { it.put("adaptive", true) }, { it.put("previewScale", 1.5) },
            { it.put("origin", "UPLOAD") }, { it.put("pipeline", "UNKNOWN") },
            { it.put("pipeline", "SHARED") }, { it.put("bakedModifiers", true) },
            { it.put("pipeline", "ADJUSTMENTS_ONLY") }
        )) {
            val invalid = JSONObject(encoded).also(mutate).toString()
            assertNull(StoredIconModifierInput.readState(invalid))
            assertNull(StoredIconModifierInput.normalize(invalid))
        }
    }

    private val artwork = StoredModifierArtwork("a".repeat(64), isXml = true, isAdaptive = true)

    private fun state(options: GenerationOptions = modifierInputTestOptions()) =
        IconModifierInput(options, false, ModifierInputOrigin.UPLOAD, liveInput = ModifierInputTestIcon())

    @Test fun roundTripRetainsEverySourceAndExportFieldWithoutEmbeddingArtwork() {
        val gradient = ColorizerStyle(firstColor = 0xff123456.toInt(), mode = ColorizerMode.GRADIENT,
            gradientStops = listOf(0xffabcdef.toInt()), gradientAngle = 45f, gradientPositions = listOf(0f, 1f))
        val options = modifierInputTestOptions().copy(
            source = IconSourceOptions(
                primarySource = Source.APPLICATION_NAME, primaryTextType = TextType.entries.last(), primaryIconPack = "primary.pack",
                secondarySource = Source.APPLICATION_ICON, secondaryImageEdit = ImageEdit.entries.last(),
                secondaryTextType = TextType.entries.first(), secondaryIconPack = "secondary.pack", textCustom = "A\nB",
                textCase = TextCase.LOWER, textFontPath = "fonts/local.ttf", fallbackSource = FallbackSource.entries.last(),
                applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU, invertMonochrome = true, useFullApplicationIcon = true
            ),
            export = IconExportOptions(vector = true, materialYou = true, themed = true,
                background = ColorFill(0xff101010.toInt(), gradient), foregroundStyle = gradient)
        )
        val original = state(options)
        val input = original.liveInput
        val encoded = checkNotNull(StoredIconModifierInput.encode(original, artwork))
        val loaded = checkNotNull(StoredIconModifierInput.readState(encoded))
        assertEquals(options, loaded.options)
        assertEquals(artwork, loaded.storedInput)
        assertEquals(ModifierInputOrigin.UPLOAD, loaded.origin)
        assertFalse(loaded.containsBakedModifierEdits)
        assertNull(loaded.liveInput)
        assertSame(input, original.liveInput)
        assertNull(original.storedInput)
        assertFalse(JSONObject(encoded).has("input"))
        assertEquals(encoded, StoredIconModifierInput.normalize(encoded))
        assertEquals(0, (input as ModifierInputTestIcon).encodeCalls)
    }

    @Test fun malformedOptionalDocumentsAreRejectedWithoutThrowing() {
        val encoded = checkNotNull(StoredIconModifierInput.encode(state(), artwork))
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("version", 999) }, { it.put("artworkHash", "../unsafe") },
            { it.put("artworkHash", "A".repeat(64)) }, { it.put("origin", "UNKNOWN") },
            { it.remove("xml") }, { it.getJSONObject("options").getJSONObject("source").put("primary", "UNKNOWN") },
            { it.getJSONObject("options").getJSONObject("source").put("text", "x".repeat(8193)) },
            { it.getJSONObject("options").put("recipe", "recipe.version=999") },
            { it.getJSONObject("options").getJSONObject("export").put("backgroundStyle", "invalid") }
        )
        for (mutate in variants) {
            val json = JSONObject(encoded).also(mutate)
            assertNull(StoredIconModifierInput.normalize(json.toString()))
            assertNull(StoredIconModifierInput.readState(json.toString()))
        }
        for (invalid in listOf(null, "", "{", "x".repeat(65537), "{\"version\":1,\"input\":\"legacy\"}")) {
            assertNull(StoredIconModifierInput.readState(invalid))
        }
    }

    @Test fun utf8ByteLimitRejectsDocumentsEvenBelowTheCharacterLimit() {
        val json = JSONObject(checkNotNull(StoredIconModifierInput.encode(state(), artwork)))
            .put("unknown", "汉".repeat(23_000))
        val encoded = json.toString()
        assertTrue(encoded.length < StoredIconModifierInput.MAX_DOCUMENT_CHARS)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size > StoredIconModifierInput.MAX_DOCUMENT_CHARS)
        assertNull(StoredIconModifierInput.readState(encoded))
    }

    @Test fun captureMarksExistingBaselinesAndRejectsLossyOptionsOrBrushMasks() {
        val input = ModifierInputTestIcon()
        for (origin in ModifierInputOrigin.entries) {
            val captured = checkNotNull(modifierInputForResult(input, modifierInputTestOptions(), origin))
            assertEquals(origin == ModifierInputOrigin.EXISTING_ICON, captured.containsBakedModifierEdits)
            assertSame(input, captured.liveInput)
        }
        val options = modifierInputTestOptions()
        assertNull(modifierInputForResult(input, options.copy(modifiers = options.modifiers.copy(
            transform = TransformModifier(scale = 100f))), ModifierInputOrigin.UPLOAD))
        val mask = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertNull(modifierInputForResult(input, options.copy(modifiers = options.modifiers.copy(
            outline = OutlineModifier(OutlineMode.ADD, .1f, ColorFill(0), mask))), ModifierInputOrigin.UPLOAD))
        assertNull(StoredIconModifierInput.encode(state(), artwork.copy(hash = "invalid")))
    }
}
