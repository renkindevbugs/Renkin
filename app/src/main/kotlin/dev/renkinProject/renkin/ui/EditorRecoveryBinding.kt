package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import dev.renkinProject.renkin.data.editor.BoundEditorResources
import dev.renkinProject.renkin.data.editor.EditorArtworkSnapshot
import dev.renkinProject.renkin.data.editor.EditorBitmapArtwork
import dev.renkinProject.renkin.data.editor.EditorCompleteCapture
import dev.renkinProject.renkin.data.editor.EditorDraftCodec
import dev.renkinProject.renkin.data.editor.EditorDraftOrigin
import dev.renkinProject.renkin.data.editor.EditorGenerationSnapshot
import dev.renkinProject.renkin.data.editor.EditorInputSnapshot
import dev.renkinProject.renkin.data.editor.EditorInsetArtwork
import dev.renkinProject.renkin.data.editor.EditorRecoveryResources
import dev.renkinProject.renkin.data.editor.EditorRecoveryValidation
import dev.renkinProject.renkin.data.editor.EditorVectorInputs
import dev.renkinProject.renkin.data.editor.restoreEditorArtwork
import dev.renkinProject.renkin.data.editor.toDraftVectorPath
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.icon.creator.BackgroundBrushOperation
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.RetainedModifierStage
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Unpublished native values only. Binding neither selects a source nor runs its generation pipeline. */
internal class BoundEditorRecovery(
    val capture: EditorCompleteCapture,
    val artwork: Map<String, IconPackDrawable>,
    val requested: GenerationOptions,
    val renderedOptions: GenerationOptions?,
    val originalInput: IconModifierInput?,
    val input: IconModifierInput?,
    val restoredExisting: VerifiedModifierInput?,
    val replay: VerifiedModifierInput?,
    val replayBaseline: GenerationOptions?,
    val vector: VectorEditState.Inputs,
    val vectorSeed: VectorEditState.Inputs?,
    val resources: BoundEditorResources,
    val createStage: RetainedModifierStage?
) {
    // The offer owner must detach every preview consumer before recycling an untransferred preparation.
    internal fun disposeUnattached() = disposeArtwork(artwork, resources)
}

internal suspend fun bindEditorRecovery(
    capture: EditorCompleteCapture, resources: EditorRecoveryResources
): BoundEditorRecovery {
    var prepared: BoundEditorRecovery? = null
    return try {
        withContext(Dispatchers.Default) {
            bindEditorRecoveryValues(capture, resources).also { prepared = it }
        }
    } catch (error: Throwable) {
        // withContext can reject a completed value when its caller is cancelled on dispatch.
        prepared?.disposeUnattached()
        throw error
    }
}

private suspend fun bindEditorRecoveryValues(
    capture: EditorCompleteCapture, resources: EditorRecoveryResources
): BoundEditorRecovery {
    val owner = currentCoroutineContext()
    owner.ensureActive()
    EditorRecoveryValidation.validate(capture)
    fun bitmapBytes(value: EditorArtworkSnapshot): Long = when (value) {
        is EditorBitmapArtwork -> value.pixels.size.toLong()
        is EditorInsetArtwork -> bitmapBytes(value.child)
        else -> 0L
    }
    val artworkBitmapBytes = capture.artwork.values.sumOf(::bitmapBytes)
    require(artworkBitmapBytes <= EditorDraftCodec.MAX_TOTAL_BYTES) { "Recovery bitmaps exceed aggregate limits" }
    val boundResources = resources.bind(capture.resources, EditorDraftCodec.MAX_TOTAL_BYTES - artworkBitmapBytes)
    val artwork = linkedMapOf<String, IconPackDrawable>()
    try {
        for ((slot, value) in capture.artwork) {
            owner.ensureActive(); artwork[slot] = restoreEditorArtwork(value)
        }
        fun mask(slot: String): Bitmap = (requireNotNull(artwork[slot]) as? BitmapIconDrawable)?.toBitmap()
            ?: throw IllegalArgumentException("Recovery mask is not a bitmap")
        fun generation(value: EditorGenerationSnapshot): GenerationOptions {
            val options = value.options
            return options.copy(modifiers = options.modifiers.copy(
                outline = options.modifiers.outline?.copy(eraseMask = value.eraseMask?.let(::mask)),
                effect = options.modifiers.effect.copy(backgroundRemoval = options.modifiers.effect.backgroundRemoval.copy(
                    brushOperations = value.backgroundMasks.map { BackgroundBrushOperation(it.action, mask(it.artwork)) }))
            ))
        }
        val inputs = mutableMapOf<EditorInputSnapshot, IconModifierInput>()
        fun input(value: EditorInputSnapshot?): IconModifierInput? = value?.let {
            inputs.getOrPut(it) { IconModifierInput(generation(it.generation), it.baked, it.origin,
                liveInput = requireNotNull(artwork[it.artwork]), pipeline = it.pipeline) }
        }
        fun vector(value: EditorVectorInputs) = VectorEditState.Inputs(
            value.entries.map { owner.ensureActive(); PathEntry(it.path.toDraftVectorPath(), it.filled, it.baseStroke) },
            value.thickness, value.autoCenter, value.viewport?.let { it.width to it.height }, value.sourceUrl, value.containsBakedModifierEdits)
        val value = capture.snapshot
        val existing = input(value.restoredExisting)?.let {
            val originalSlot = requireNotNull(value.original.base ?: value.original.rendered) { "Original replay result is missing" }
            val original = requireNotNull(artwork[originalSlot]) { "Original replay result is missing" }
            requireNotNull(VerifiedModifierInput.verify(resources.androidResources, requireNotNull(it.liveInput), it, original)) {
                "Recovered original modifier stage does not match its artwork"
            }
        }
        return BoundEditorRecovery(capture, artwork.toMap(), generation(value.requested), value.renderedOptions?.let(::generation),
            input(value.original.input), input(value.input), existing, existing.takeIf { value.replay != null },
            value.replay?.baseline?.let(::generation), vector(value.vector.current), value.vector.seed?.let(::vector), boundResources,
            if (value.origin == EditorDraftOrigin.CREATE && value.selections.isNotEmpty()) RetainedModifierStage(resources.androidResources,
                requireNotNull(artwork[requireNotNull(value.raw)]), value.pipeline, value.rawBaked,
                value.input?.origin ?: ModifierInputOrigin.FRESH_SOURCE) else null)
            .also { owner.ensureActive() }
    } catch (error: Throwable) {
        disposeArtwork(artwork, boundResources)
        throw error
    }
}

private fun disposeArtwork(artwork: Map<String, IconPackDrawable>, resources: BoundEditorResources) {
    fun dispose(icon: IconPackDrawable) {
        when (icon) {
            is BitmapIconDrawable -> icon.toBitmap().let { if (!it.isRecycled) it.recycle() }
            is InsetIconDrawable -> (icon.drawable as? IconPackDrawable)?.let(::dispose)
        }
    }
    artwork.values.forEach(::dispose)
    resources.upload?.let { if (!it.isRecycled) it.recycle() }
}
