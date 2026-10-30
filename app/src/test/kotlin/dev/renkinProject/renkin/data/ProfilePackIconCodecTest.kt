package dev.renkinProject.renkin.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import dev.renkinProject.renkin.icon.creator.modifierInputForResult
import dev.renkinProject.renkin.icon.creator.modifierInputTestOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePackIconCodecTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val codec get() = ProfilePackIconCodec(context)

    private fun bitmap(color: Int, size: Int = 8) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }

    @Test fun emptyValueKeepsTheDefaultIcon() = runBlocking {
        assertEquals("", codec.encode(null))
        assertNull(codec.decode(""))
        assertNull(ProfilePackIconCodec.sourcePackName(""))
    }

    @Test fun rasterKeepsPixelsAdaptiveGeometryAndAttribution() = runBlocking {
        val icon = BitmapIconDrawable(bitmap(Color.RED), exportAsAdaptiveIcon = true, previewScale = 1.5f)
        val encoded = codec.encode(icon, "pack.source", "https://example.org/icon.svg")
        val restored = checkNotNull(codec.decode(encoded))
        assertEquals(Color.RED, restored.icon.toBitmap().getPixel(3, 3))
        assertTrue(restored.icon.isAdaptiveIcon())
        assertEquals(1.5f, (restored.icon as BitmapIconDrawable).previewScale)
        assertEquals("pack.source", restored.sourcePackName)
        assertEquals("https://example.org/icon.svg", restored.sourceUrl)
        assertEquals("pack.source", ProfilePackIconCodec.sourcePackName(encoded))
    }

    @Test fun adaptiveIconRetainsIndependentLayersInsteadOfFlattening() = runBlocking {
        fun layer(color: Int) = bitmap(color, AdaptiveIconPackDrawable.LAYER_SIZE).getBytes(Bitmap.CompressFormat.PNG, 100)
        val icon = AdaptiveIconPackDrawable(layer(Color.RED), layer(Color.BLUE), layer(Color.WHITE))
        val restored = checkNotNull(codec.decode(codec.encode(icon))).icon as AdaptiveIconPackDrawable
        assertArrayEquals(icon.foregroundPng, restored.foregroundPng)
        assertArrayEquals(icon.backgroundPng, restored.backgroundPng)
        assertArrayEquals(icon.monochromePng, restored.monochromePng)
        assertTrue(restored.isAdaptiveIcon())
    }

    @Test fun storedModifierSourceBecomesSelfContainedAndSurvivesSourceFileDeletion() = runBlocking {
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val source = BitmapIconDrawable(bitmap(Color.RED))
        val state = checkNotNull(modifierInputForResult(source, modifierInputTestOptions(), ModifierInputOrigin.UPLOAD))
        val store = IconModifierInputStore(context)
        val prepared = checkNotNull(store.prepare(state))
        store.accept(state, prepared)
        assertNull(state.liveInput)
        val recipe = checkNotNull(StoredIconModifierRecipe.fromRenderedOptions(state.options))
        val encoded = codec.encode(BitmapIconDrawable(bitmap(Color.GREEN)), modifierRecipe = recipe, modifierInput = state)
        assertTrue(IconModifierInputStore.file(context, prepared.artwork.hash).delete())

        val restored = checkNotNull(codec.decode(encoded))
        assertEquals(Color.GREEN, restored.icon.toBitmap().getPixel(3, 3))
        assertEquals(recipe, restored.modifierRecipe)
        val input = checkNotNull(restored.modifierInput)
        assertEquals(state.options, input.options)
        assertEquals(state.origin, input.origin)
        assertEquals(state.pipeline, input.pipeline)
        assertNull(input.storedInput)
        assertEquals(Color.RED, checkNotNull(input.liveInput).toBitmap().getPixel(3, 3))
    }

    @Test fun changedModifierSourceCannotMasqueradeAsTheRetainedInput() = runBlocking {
        val state = checkNotNull(modifierInputForResult(BitmapIconDrawable(bitmap(Color.RED)),
            modifierInputTestOptions(), ModifierInputOrigin.UPLOAD))
        val encoded = JSONObject(codec.encode(BitmapIconDrawable(bitmap(Color.GREEN)), modifierInput = state))
        encoded.getJSONObject("inputArtwork").put("payload", BitmapIconDrawable(bitmap(Color.BLUE)).toDbString())
        assertTrue(runCatching { codec.decode(encoded.toString()) }.isFailure)
    }

    @Test fun invalidAndOversizedDocumentsAreRejectedBeforeArtworkDecode() = runBlocking {
        assertTrue(runCatching { ProfilePackIconCodec.normalize(" ".repeat(ProfilePackIconCodec.MAX_DOCUMENT_CHARS + 1)) }.isFailure)
        val encoded = codec.encode(BitmapIconDrawable(bitmap(Color.RED)))
        val unsupported = JSONObject(encoded).put("version", 999).toString()
        assertTrue(runCatching { ProfilePackIconCodec.normalize(unsupported) }.isFailure)
        val badScale = JSONObject(encoded).apply { getJSONObject("icon").put("previewScale", 100) }.toString()
        assertTrue(runCatching { ProfilePackIconCodec.normalize(badScale) }.isFailure)
        val incomplete = JSONObject(encoded).put("inputArtwork", JSONObject(encoded).getJSONObject("icon")).toString()
        assertTrue(runCatching { ProfilePackIconCodec.normalize(incomplete) }.isFailure)
    }
}
