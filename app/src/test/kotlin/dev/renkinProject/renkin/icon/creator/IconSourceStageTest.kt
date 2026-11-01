package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconSourceStageTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private fun input() = BitmapIconDrawable(resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
    })
    private fun options() = modifierInputTestOptions().copy(modifiers = ModifierStack(
        EffectModifier(ImageEdit.COLORIZE, ColorizerStyle(firstColor = Color.BLUE, flat = true))
    ))

    @Test fun canonicalStageRetainsUnmodifiedPixelsAndReplaysTheCompletedResult() {
        val input = input()
        val options = options()
        val result = IconSourceStage(input, ModifierInputPipeline.BITMAP_IMAGE_EDIT, ImageEdit.COLORIZE)
            .render(IconModifierStageRenderer(resources, options), retainInput = true)
        val retained = checkNotNull(result.modifierInput)
        assertSame(input, retained.liveInput)
        assertEquals(Color.RED, input.toBitmap().getPixel(3, 3))
        assertEquals(Color.BLUE, result.icon!!.toBitmap().getPixel(3, 3))
        val replay = IconModifierStageRenderer(resources, retained.options).render(input, retained.pipeline)
        assertTrue(result.icon.toBitmap().sameAs(replay.toBitmap()))
    }

    @Test fun textStageRecordsNoImageEffectEvenWhenInputCaptureIsUnavailable() {
        val input = input()
        val options = options().withSource { copy(primarySource = Source.APPLICATION_NAME) }
        for (retainable in listOf(true, false)) {
            val result = IconSourceStage(input, ModifierInputPipeline.ADJUSTMENTS_ONLY, ImageEdit.COLORIZE, retainable)
                .render(IconModifierStageRenderer(resources, options), retainInput = true)
            assertSame(input, result.icon)
            assertEquals(ImageEdit.NONE, result.renderedOptions!!.modifiers.effect.imageEdit)
            val recipe = checkNotNull(StoredIconModifierRecipe.fromRenderedOptions(result.renderedOptions))
            assertEquals(ImageEdit.NONE, ModifierRecipeCodec.decode(recipe)!!.effect.imageEdit)
            assertEquals(retainable, result.modifierInput != null)
        }
    }

    @Test fun secondaryEffectOverridesThePrimaryEffectWithoutChangingRendererSettings() {
        val options = options()
        val renderer = IconModifierStageRenderer(resources, options)
        val input = input()
        val result = IconSourceStage(input, ModifierInputPipeline.IMAGE_EDIT, ImageEdit.NONE)
            .render(renderer, retainInput = true)
        assertSame(input, result.icon)
        assertEquals(ImageEdit.NONE, result.modifierInput!!.options.modifiers.effect.imageEdit)
        assertEquals(ImageEdit.COLORIZE, renderer.options.modifiers.effect.imageEdit)
        assertSame(renderer.adjustments, renderer.adjustments)
        assertSame(renderer.imageEdits, renderer.imageEdits)
    }

    @Test fun unsupportedPackRestylingAndNonRetainingCallsKeepArtworkWithoutAnInput() {
        val input = input()
        val renderer = IconModifierStageRenderer(resources, options())
        val result = IconSourceStage(input, ModifierInputPipeline.IMAGE_EDIT, ImageEdit.COLORIZE, retainable = false)
            .render(renderer, retainInput = true)
        assertNotNull(result.icon)
        assertNull(result.modifierInput)
        val bulk = IconSourceStage(input, ModifierInputPipeline.IMAGE_EDIT, ImageEdit.COLORIZE)
            .render(renderer, retainInput = false)
        assertTrue(result.icon!!.toBitmap().sameAs(bulk.icon!!.toBitmap()))
        assertNull(bulk.modifierInput)
        assertNull(bulk.renderedOptions)
    }
}
