package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.MainThread
import dev.renkinProject.renkin.data.editor.*
import dev.renkinProject.renkin.drawable.*
import dev.renkinProject.renkin.icon.creator.*
import java.util.IdentityHashMap

/** Native objects never escape this Main-owned collector except captured immutable input-file handles. */
internal class EditorCompleteMainCapture private constructor(
    val witness: EditorRenderWitness,
    val serial: Long,
    private val snapshot: EditorCompleteSnapshot,
    private val collector: ArtworkCollector
) {
    internal data class PendingInput(val slot: String, val retained: IconModifierInput)
    val pendingInputs: List<PendingInput> get() = collector.pending.toList()

    @MainThread
    fun attachInput(slot: String, artwork: IconPackDrawable) = collector.attach(slot, artwork)

    @MainThread
    fun finish(resources: Map<String, EditorDraftResource>): EditorCompleteCapture {
        val references = resources.map { (slot, resource) ->
            EditorResourceReference(resource.kind, resource.selectionReference, slot, resource.sha256)
        }
        return EditorCompleteCapture(snapshot.copy(resources = references), collector.artwork.toMap(), resources.toMap())
    }

    companion object {
        @MainThread
        fun capture(session: EditorSession, ownedResourceBytes: Long): EditorCompleteMainCapture {
            val witness = session.captureRenderWitness()
            session.requirePublishedSources(witness)
            val state = session.draft.captureState(witness)
            require(state.replay === session.modifierReplay?.verified) { "Editor replay ownership mismatch" }
            require(witness.selections.size <= 1) { "Unsupported editor source selection count" }
            val collector = ArtworkCollector(if (witness.upload.path == null) 1 else 2, ownedResourceBytes)
            val raw = collector.add(state.raw)
            val rendered = collector.add(state.artwork)
            val retained = collector.input(state.input, state.raw.takeIf { state.rendered != null })
            val app = session.application
            val verifiedOriginalInput = state.restoredExisting?.takeIf { it.retained === app.modifierInput }?.input
            val original = EditorOriginalSnapshot(app.appName, app.originalName,
                collector.add(app.baseIcon), collector.add(app.createdIcon), app.modifierRecipe,
                collector.input(app.modifierInput, verifiedOriginalInput), EditorCalendarControls(app.calendarEnabled, app.calendarPrefix, app.calendarPackName),
                app.sourcePackName, app.sourceUrl, app.isFallback, app.isRefreshMade, app.isCustom, app.isLegacy)
            val requested = collector.generation(state.requested)
            val renderedOptions = state.rendered?.let(collector::generation)
            val restoredSource = collector.add(session.restoredIconSource)
            val existing = state.restoredExisting?.let { collector.input(it.retained, it.input) }
            val replay = session.modifierReplay?.let {
                val bookkeeping = it.captureBookkeeping()
                EditorReplaySnapshot(requireNotNull(collector.input(it.verified.retained, it.verified.input)),
                    collector.generation(state.requested.copy(modifiers = bookkeeping.baseline)), bookkeeping.plate?.detached())
            }
            val selections = witness.selections.map {
                EditorSourceSelection(it.resourceId, witness.controls.create.iconPack,
                    requireNotNull(raw) { "Selected editor source has no captured stage input" })
            }
            val snapshot = EditorCompleteSnapshot(witness.controls, session.vectorEditState.captureArtworkState(),
                state.serial, EditorDraftOrigin.valueOf(state.origin.name), state.initialized, state.canRestore,
                state.vectorContainsBakedModifierEdits, session.restoredSourceBakedForDraft(), session.modifierRestoreFailed,
                original, raw, rendered, state.recipe, requested, renderedOptions, state.pipeline, state.rawContainsBakedModifierEdits, retained,
                restoredSource, existing, replay, selections, witness.upload.path, witness.onlinePath, emptyList())
            return EditorCompleteMainCapture(witness, state.serial, snapshot, collector)
        }
    }
}

private class ArtworkCollector(private val resourceCount: Int, resourceBytes: Long) {
    val artwork = linkedMapOf<String, EditorArtworkSnapshot>()
    val pending = mutableListOf<EditorCompleteMainCapture.PendingInput>()
    private val icons = IdentityHashMap<IconPackDrawable, String>()
    private val bitmaps = IdentityHashMap<Bitmap, String>()
    private val inputs = IdentityHashMap<IconModifierInput, EditorInputSnapshot>()
    private var slots = 0
    private var maskBytes = 0L
    private var copiedBytes = resourceBytes.also { require(it in 1..EditorDraftCodec.MAX_TOTAL_BYTES) }

    private fun reserve(): String {
        val maskArtifact = if (bitmaps.isEmpty()) 0 else 1
        require(3 + resourceCount + slots + maskArtifact < EditorDraftCodec.MAX_ARTIFACTS) { "Complete editor draft has too many artifacts" }
        return "artwork-${slots++}"
    }

    fun add(icon: IconPackDrawable?): String? {
        icon ?: return null
        icons[icon]?.let { return it }
        val slot = reserve()
        attach(slot, icon)
        icons[icon] = slot
        return slot
    }

    fun attach(slot: String, icon: IconPackDrawable) {
        require(slot !in artwork)
        preflight(icon, EditorArtworkBudget(), 0)
        artwork[slot] = captureEditorArtwork(icon)
    }

    private fun preflight(icon: IconPackDrawable, budget: EditorArtworkBudget, depth: Int) {
        budget.node(depth)
        fun bytes(count: Int) {
            budget.bytes(count)
            require(copiedBytes + count <= EditorDraftCodec.MAX_TOTAL_BYTES) { "Complete editor artwork exceeds byte limits" }
            copiedBytes += count
        }
        when (icon) {
            is BitmapIconDrawable -> {
                val bitmap = icon.toBitmap()
                require(!bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888 &&
                    (Build.VERSION.SDK_INT < 26 || bitmap.colorSpace?.isSrgb == true) &&
                    bitmap.width in 1..4096 && bitmap.height in 1..4096) { "Unsupported editor bitmap" }
                bytes(bitmap.byteCount)
            }
            is AdaptiveIconPackDrawable -> {
                bytes(icon.foregroundPng.size); bytes(icon.backgroundPng.size)
                icon.monochromePng?.let { bytes(it.size) }
                icon.originalForegroundPng?.let { bytes(it.size) }; icon.originalBackgroundPng?.let { bytes(it.size) }
            }
            is InsetIconDrawable -> preflight(icon.drawable as? IconPackDrawable
                ?: throw IllegalArgumentException("Unsupported editor inset"), budget, depth + 1)
            is ImageVectorDrawable -> Unit // The exact adapter preflights paths before copying them.
            else -> throw IllegalArgumentException("Unsupported editor artwork")
        }
    }

    private fun mask(bitmap: Bitmap): String {
        bitmaps[bitmap]?.let { return it }
        require(bitmaps.size < EditorCompleteCodec.MAX_MASKS) { "Too many editor masks" }
        require(3 + resourceCount + slots + 1 <= EditorDraftCodec.MAX_ARTIFACTS) { "Complete editor draft has too many artifacts" }
        require(bitmap.byteCount.toLong() <= EditorDraftCodec.MAX_ARTIFACT_BYTES - maskBytes) { "Editor masks exceed byte limits" }
        val slot = "mask-${bitmaps.size}"
        // Mask presentation is independent of any icon that happens to share these pixels.
        attach(slot, BitmapIconDrawable(bitmap, false))
        maskBytes += bitmap.byteCount
        bitmaps[bitmap] = slot
        return slot
    }

    fun generation(value: GenerationOptions): EditorGenerationSnapshot {
        val effect = value.modifiers.effect
        val removal = effect.backgroundRemoval
        require(removal.targets.size <= EditorControlsCodec.MAX_STYLE_VALUES &&
            effect.segments.size <= EditorControlsCodec.MAX_SEGMENTS &&
            removal.brushOperations.size <= EditorControlsCodec.MAX_STROKES)
        val masks = removal.brushOperations.map { EditorMaskReference(it.action, mask(it.mask)) }
        val outline = value.modifiers.outline
        val erase = outline?.eraseMask?.let(::mask)
        val detached = value.copy(
            export = value.export.copy(background = value.export.background.detached(), foregroundStyle = value.export.foregroundStyle?.detached()),
            modifiers = value.modifiers.copy(
                effect = effect.copy(colorizer = effect.colorizer.detached(),
                    backgroundRemoval = removal.copy(targets = removal.targets.toList(), brushOperations = emptyList()),
                    segments = effect.segments.map {
                        require(it.targets.size <= EditorControlsCodec.MAX_STYLE_VALUES)
                        it.copy(targets = it.targets.toList(), style = it.style.detached())
                    }),
                shape = value.modifiers.shape?.let { it.copy(fill = it.fill?.detached()) },
                outline = outline?.copy(fill = outline.fill.detached(), eraseMask = null),
                shadow = value.modifiers.shadow?.let { it.copy(style = it.style.detached()) },
                materialYouPack = value.modifiers.materialYouPack.let {
                    it.copy(customForeground = it.customForeground?.detached(), customBackground = it.customBackground?.detached())
                }
            )
        )
        return EditorGenerationSnapshot(detached, erase, masks)
    }

    fun input(value: IconModifierInput?, verifiedArtwork: IconPackDrawable? = null): EditorInputSnapshot? {
        value ?: return null
        inputs[value]?.let { return it }
        val generation = generation(value.options)
        val (live, stored) = synchronized(value) { value.liveInput to value.storedInput }
        val icon = verifiedArtwork ?: live
        val slot = if (icon != null) requireNotNull(add(icon)) else {
            val descriptor = requireNotNull(stored) { "Editor modifier input artwork is unavailable" }
            reserve().also {
                pending += EditorCompleteMainCapture.PendingInput(it,
                    IconModifierInput(generation.options, value.containsBakedModifierEdits, value.origin,
                        storedInput = descriptor, pipeline = value.pipeline))
            }
        }
        return EditorInputSnapshot(slot, generation, value.containsBakedModifierEdits, value.origin, value.pipeline).also { inputs[value] = it }
    }
}

private fun ColorizerStyle.detached(): ColorizerStyle {
    require(gradientStops.size <= EditorControlsCodec.MAX_STYLE_VALUES && gradientPositions.size <= EditorControlsCodec.MAX_STYLE_VALUES)
    return copy(gradientStops = gradientStops.toList(), gradientPositions = gradientPositions.toList())
}
private fun ColorFill.detached() = copy(style = style?.detached())
