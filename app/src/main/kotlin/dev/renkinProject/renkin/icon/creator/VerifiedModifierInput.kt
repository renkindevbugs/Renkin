package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class VerifiedModifierInput private constructor(
    private val resources: Resources,
    val input: IconPackDrawable,
    val retained: IconModifierInput
) {
    suspend fun render(options: GenerationOptions): IconGenerationResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val icon = try {
            IconModifierStageRenderer(resources, options).render(input, retained.pipeline)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("VerifiedModifierInput", "Retained modifier preview failed", error)
            return@withContext IconGenerationResult(null)
        } catch (error: OutOfMemoryError) {
            Log.error("VerifiedModifierInput", "Retained modifier preview exceeded available memory", error)
            return@withContext IconGenerationResult(null)
        }
        currentCoroutineContext().ensureActive()
        val metadata = try {
            val recipe = StoredIconModifierRecipe.fromRenderedOptions(options)
            val normalized = recipe?.let(ModifierRecipeCodec::decode)?.let { options.copy(modifiers = it) }
            normalized?.takeIf(StoredIconModifierInput::supportsOptions)?.let {
                IconModifierInput(it, retained.containsBakedModifierEdits, retained.origin,
                    liveInput = input, storedInput = retained.storedInput, pipeline = retained.pipeline)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("VerifiedModifierInput", "Optional replay metadata was not captured", error)
            null
        } catch (error: OutOfMemoryError) {
            null
        }
        currentCoroutineContext().ensureActive()
        IconGenerationResult(icon, metadata, options)
    }

    companion object {
        suspend fun verify(
            resources: Resources, input: IconPackDrawable, retained: IconModifierInput, base: IconPackDrawable
        ): VerifiedModifierInput? = withContext(Dispatchers.Default) {
            try {
                if (retained.options.modifiers.materialYouPack.hasRestyling) return@withContext null
                currentCoroutineContext().ensureActive()
                val replay = IconModifierStageRenderer(resources, retained.options).render(input, retained.pipeline)
                currentCoroutineContext().ensureActive()
                val matches = sameLocalArtwork(base, replay)
                currentCoroutineContext().ensureActive()
                if (matches) VerifiedModifierInput(resources, input, retained) else null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("VerifiedModifierInput", "Retained modifier stage could not be verified", error)
                null
            } catch (error: OutOfMemoryError) {
                Log.error("VerifiedModifierInput", "Retained modifier replay exceeded available memory", error)
                null
            }
        }

        private fun sameLocalArtwork(base: IconPackDrawable, replay: IconPackDrawable): Boolean {
            if (base.javaClass != replay.javaClass || base.isAdaptiveIcon() != replay.isAdaptiveIcon()) return false
            if (base is BitmapIconDrawable && replay is BitmapIconDrawable) {
                return base.previewScale == replay.previewScale && base.toBitmap().sameAs(replay.toBitmap())
            }
            // Exact XML covers all adaptive layers and vector geometry; raster comparison also
            // checks resolved reference colours and preview behaviour omitted by serialization.
            return base.toDbString() == replay.toDbString() &&
                base.toModifierBitmap().sameAs(replay.toModifierBitmap())
        }
    }
}

internal data class PreparedModifierReplay(val verified: VerifiedModifierInput, val storageEpoch: Long)
