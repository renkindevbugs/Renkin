package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException

internal class IconSourceStage(
    private val input: IconPackDrawable,
    private val pipeline: ModifierInputPipeline,
    private val imageEdit: ImageEdit,
    private val retainable: Boolean = true
) {
    fun render(renderer: IconModifierStageRenderer, retainInput: Boolean): IconGenerationResult {
        val effectiveImageEdit = if (pipeline == ModifierInputPipeline.ADJUSTMENTS_ONLY) ImageEdit.NONE else imageEdit
        val rendered = renderer.render(input, pipeline, effectiveImageEdit)
        if (!retainInput) return IconGenerationResult(rendered)
        val effective = renderer.options.let { options ->
            if (options.modifiers.effect.imageEdit == effectiveImageEdit) options
            else options.withModifiers { withEffect { copy(imageEdit = effectiveImageEdit) } }
        }
        val retained = if (retainable) retain(effective) else null
        return IconGenerationResult(rendered, retained, effective, input, pipeline)
    }

    private fun retain(options: GenerationOptions): IconModifierInput? = try {
        modifierInputForResult(input, options, ModifierInputOrigin.FRESH_SOURCE, pipeline)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.error("IconSourceStage", "Optional source stage could not be retained", error)
        null
    } catch (error: OutOfMemoryError) {
        Log.error("IconSourceStage", "Optional source stage exceeded available memory", error)
        null
    }
}

internal class IconModifierStageRenderer(resources: Resources, val options: GenerationOptions) {
    val adjustments by lazy { IconAdjustmentPipeline(resources, options) }
    val imageEdits by lazy { IconImageEditPipeline(resources, options, adjustments) }

    fun render(
        input: IconPackDrawable,
        pipeline: ModifierInputPipeline,
        imageEdit: ImageEdit = options.modifiers.effect.imageEdit
    ): IconPackDrawable {
        if (pipeline == ModifierInputPipeline.ADJUSTMENTS_ONLY) return adjustments.apply(input)
        if (pipeline == ModifierInputPipeline.SHARED) return imageEdits.apply(input, imageEdit)
        val edited = when (pipeline) {
            ModifierInputPipeline.IMAGE_EDIT -> imageEdits.applyEdit(input, imageEdit)
            ModifierInputPipeline.BITMAP_IMAGE_EDIT -> imageEdits.applyToBitmap(
                input.toBitmap(), imageEdit, options.modifiers.effect.colorizer.blendMode
            )
            else -> error("Unsupported source stage pipeline")
        }
        return adjustments.apply(edited)
    }
}
