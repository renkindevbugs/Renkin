package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Source parsing/restyling is already baked into input. Resource ids are never resolved here.
internal class RetainedModifierStage(
    private val resources: Resources,
    val input: IconPackDrawable,
    val pipeline: ModifierInputPipeline,
    val containsBakedModifierEdits: Boolean,
    private val origin: ModifierInputOrigin,
    private val storedInput: StoredModifierArtwork? = null
) {
    suspend fun render(options: GenerationOptions): IconGenerationResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val icon = try {
            IconModifierStageRenderer(resources, options).render(input, pipeline)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("RetainedModifierStage", "Retained modifier preview failed", error)
            return@withContext IconGenerationResult(null)
        } catch (error: OutOfMemoryError) {
            Log.error("RetainedModifierStage", "Retained modifier preview exceeded available memory", error)
            return@withContext IconGenerationResult(null)
        }
        currentCoroutineContext().ensureActive()
        val metadata = try {
            val recipe = StoredIconModifierRecipe.fromRenderedOptions(options)
            val normalized = recipe?.let(ModifierRecipeCodec::decode)?.let { options.copy(modifiers = it) }
            normalized?.takeIf(StoredIconModifierInput::supportsOptions)?.let {
                IconModifierInput(it, containsBakedModifierEdits, origin,
                    liveInput = input, storedInput = storedInput, pipeline = pipeline)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("RetainedModifierStage", "Optional replay metadata was not captured", error)
            null
        } catch (error: OutOfMemoryError) {
            null
        }
        currentCoroutineContext().ensureActive()
        IconGenerationResult(icon, metadata, options, input, pipeline)
    }
}
