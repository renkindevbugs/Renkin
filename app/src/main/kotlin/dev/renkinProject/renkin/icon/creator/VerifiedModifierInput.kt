package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
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
    suspend fun render(options: GenerationOptions): IconGenerationResult = RetainedModifierStage(
        resources, input, retained.pipeline, retained.containsBakedModifierEdits,
        retained.origin, retained.storedInput
    ).render(options)

    companion object {
        suspend fun verify(
            resources: Resources, input: IconPackDrawable, retained: IconModifierInput, base: IconPackDrawable
        ): VerifiedModifierInput? = withContext(Dispatchers.Default) {
            try {
                val adaptiveLayers = input is AdaptiveIconPackDrawable && retained.pipeline == ModifierInputPipeline.IMAGE_EDIT
                if (!retained.options.modifiers.materialYouPack.isReplayableOn(adaptiveLayers)) return@withContext null
                val renderer = IconModifierStageRenderer(resources, retained.options)
                suspend fun replays(frameInput: IconPackDrawable): Boolean {
                    currentCoroutineContext().ensureActive()
                    val replay = renderer.renderRetained(frameInput, retained.pipeline)
                    currentCoroutineContext().ensureActive()
                    return sameLocalArtwork(base, replay)
                }
                // Adaptive icons flattened before modifiers started from the launcher-sized frame
                // (488c37e6) stored the smaller output. The recipe is still genuine; restoring it
                // renders the corrected size, so the next Apply upgrades the stored icon.
                val matches = replays(input) ||
                    (input is AdaptiveIconPackDrawable && replays(input.withLegacyModifierFrame()))
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
