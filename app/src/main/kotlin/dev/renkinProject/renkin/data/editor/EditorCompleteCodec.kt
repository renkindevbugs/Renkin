package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.ModifierInputPipeline
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object EditorCompleteCodec {
    const val VERSION = 1
    private const val MAGIC = 0x524b4543 // RKEC
    private const val MASK_MAGIC = 0x524b454d // RKEM
    private const val MASK_VERSION = 1
    private const val MAX_RECIPE_BYTES = 1024 * 1024
    internal const val MAX_MASKS = EditorArtworkBudget.MAX_NODES
    private val artworkSlot = Regex("artwork-[0-9]+")
    private val maskSlot = Regex("mask-[0-9]+")

    internal fun isMaskSlot(slot: String): Boolean = maskSlot.matches(slot)

    suspend fun encode(capture: EditorCompleteCapture): Map<String, ByteArray> = withContext(Dispatchers.Default) {
        validate(capture)
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        fun add(name: String, bytes: ByteArray) {
            val limit = if (name == "controls") EditorDraftCodec.MAX_CONTROLS_BYTES else EditorDraftCodec.MAX_ARTIFACT_BYTES
            require(bytes.size.toLong() in 1..limit && total + bytes.size <= EditorDraftCodec.MAX_TOTAL_BYTES) { "Complete editor draft exceeds byte limits" }
            require(result.size < EditorDraftCodec.MAX_ARTIFACTS && name !in result) { "Complete editor draft exceeds artifact limits" }
            total += bytes.size
            result[name] = bytes
        }
        val controls = EditorControlsCodec.encode(capture.snapshot.controls)
        add("controls", controls)
        add("session", encodeSession(capture.snapshot, controls))
        add("vector", EditorArtworkCodec.encodeVectorState(capture.snapshot.vector))
        for ((slot, artwork) in capture.artwork.filterKeys { !isMaskSlot(it) }) {
            currentCoroutineContext().ensureActive(); add(slot, EditorArtworkCodec.encode(artwork))
        }
        val masks = capture.artwork.filterKeys(::isMaskSlot)
        if (masks.isNotEmpty()) add("masks", encodeMasks(masks))
        for ((slot, resource) in capture.resources) { currentCoroutineContext().ensureActive(); add(slot, resource.copyBytes()) }
        result
    }

    suspend fun decode(payloads: Map<String, ByteArray>): EditorCompleteCapture = withContext(Dispatchers.Default) {
        require(payloads.size in 3..EditorDraftCodec.MAX_ARTIFACTS && payloads.values.sumOf { it.size.toLong() } <= EditorDraftCodec.MAX_TOTAL_BYTES)
        payloads.forEach { (slot, bytes) ->
            val limit = if (slot == "controls") EditorDraftCodec.MAX_CONTROLS_BYTES else EditorDraftCodec.MAX_ARTIFACT_BYTES
            require(bytes.size.toLong() in 1..limit)
        }
        val controlsBytes = requireNotNull(payloads["controls"]) { "Missing editor controls artifact" }
        val vectorBytes = requireNotNull(payloads["vector"]) { "Missing editor vector artifact" }
        val sessionBytes = requireNotNull(payloads["session"]) { "Missing editor session artifact" }
        val controls = EditorControlsCodec.decode(controlsBytes)
        val vector = EditorArtworkCodec.decodeVectorState(vectorBytes)
        val snapshot = decodeSession(sessionBytes, controlsBytes, controls, vector)
        val artwork = linkedMapOf<String, EditorArtworkSnapshot>()
        val resources = linkedMapOf<String, EditorDraftResource>()
        for ((slot, bytes) in payloads) {
            currentCoroutineContext().ensureActive()
            when {
                slot in setOf("controls", "session", "vector") -> Unit
                artworkSlot.matches(slot) -> artwork[slot] = EditorArtworkCodec.decode(bytes)
                slot == "masks" -> artwork.putAll(decodeMasks(bytes))
                else -> {
                    val reference = snapshot.resources.singleOrNull { it.artifact == slot }
                        ?: throw IllegalArgumentException("Unknown editor draft artifact")
                    resources[slot] = EditorDraftResource(reference.kind, reference.selection, bytes)
                }
            }
        }
        EditorCompleteCapture(snapshot, artwork, resources).also(::validate)
    }

    internal fun validate(capture: EditorCompleteCapture) {
        val value = capture.snapshot
        require(value.serial > 0) { "Incomplete editor frame" }
        require(capture.artwork.keys.all { artworkSlot.matches(it) || isMaskSlot(it) }) { "Invalid editor artwork slot" }
        require(capture.artwork.keys.count(::isMaskSlot) <= MAX_MASKS) { "Too many editor masks" }
        require(value.vector.initialized == value.controls.vector.initialized &&
            value.vector.current.thickness.toBits() == value.controls.vector.thickness.toBits() &&
            value.vector.current.autoCenter == value.controls.vector.automaticallyCenter &&
            value.vector.current.viewport == value.controls.vector.viewport &&
            value.vector.current.sourceUrl == value.controls.vector.sourceUrl &&
            value.vector.current.containsBakedModifierEdits == value.controls.vector.containsBakedModifierEdits) { "Vector/control mismatch" }
        require(!value.vector.initialized || value.vector.seed != null)
        val referenced = linkedSetOf<String>()
        fun artwork(slot: String?) { slot?.let { require(it in capture.artwork) { "Missing editor artwork" }; referenced += it } }
        fun generation(generation: EditorGenerationSnapshot?) {
            generation ?: return
            require(generation.options.modifiers.outline?.eraseMask == null &&
                generation.options.modifiers.effect.backgroundRemoval.brushOperations.isEmpty())
            (listOfNotNull(generation.eraseMask) + generation.backgroundMasks.map { it.artwork }).forEach {
                artwork(it); require(isMaskSlot(it) && capture.artwork[it] is EditorBitmapArtwork) { "Invalid editor mask" }
            }
        }
        fun input(input: EditorInputSnapshot?) { input?.let { artwork(it.artwork); generation(it.generation) } }
        artwork(value.original.base); artwork(value.original.rendered); input(value.original.input)
        artwork(value.raw); artwork(value.rendered); artwork(value.restoredSource)
        generation(value.requested); generation(value.renderedOptions); input(value.input); input(value.restoredExisting)
        value.replay?.let { input(it.input); generation(it.baseline); require(value.restoredExisting != null && value.restoredSource != null) }
        require(value.selections.size <= 1) { "Unsupported editor source selection count" }
        value.selections.forEach { require(it.pack == value.controls.create.iconPack && it.artwork == value.raw); artwork(it.artwork) }
        require(value.raw != null && value.rendered != null) { "Completed editor artwork is missing" }
        if (value.origin == EditorDraftOrigin.VECTOR) require(value.vector.initialized &&
            value.vectorBaked == value.vector.current.containsBakedModifierEdits && value.rawBaked == value.vectorBaked)
        if (value.origin == EditorDraftOrigin.UPLOAD) require(!value.rawBaked)
        if (value.renderedOptions != null) value.input?.let {
            require(it.artwork == value.raw && it.pipeline == value.pipeline && it.baked == value.rawBaked) { "Completed editor input mismatch" }
        }
        require(referenced == capture.artwork.keys) { "Unreferenced editor artwork" }
        require(value.resources.map { it.artifact }.toSet().size == value.resources.size)
        require(value.resources.map { it.kind }.toSet().size == value.resources.size)
        require(capture.resources.keys == value.resources.map { it.artifact }.toSet())
        for (reference in value.resources) {
            val resource = capture.resources.getValue(reference.artifact)
            require(resource.kind == reference.kind && resource.selectionReference == reference.selection && resource.sha256 == reference.sha256) { "Editor resource/selection mismatch" }
            when (reference.kind) {
                EditorDraftResourceKind.FONT -> require(reference.artifact == "font" && reference.selection == value.controls.create.fontReference)
                EditorDraftResourceKind.UPLOAD -> require(reference.artifact == "upload" && reference.selection == value.uploadPath)
            }
        }
        require(value.resources.any { it.kind == EditorDraftResourceKind.FONT }) { "Owned editor font is missing" }
        require((value.uploadPath != null) == value.resources.any { it.kind == EditorDraftResourceKind.UPLOAD }) { "Owned editor upload is missing" }
        val artworkArtifacts = capture.artwork.keys.count { !isMaskSlot(it) } +
            (if (capture.artwork.keys.any(::isMaskSlot)) 1 else 0)
        require(artworkArtifacts + capture.resources.size + 3 <= EditorDraftCodec.MAX_ARTIFACTS)
    }

    private suspend fun encodeMasks(masks: Map<String, EditorArtworkSnapshot>): ByteArray {
        val owner = currentCoroutineContext()
        return EditorArtworkWriter { owner.ensureActive() }.run {
            output.writeInt(MASK_MAGIC); output.writeInt(MASK_VERSION); output.writeInt(masks.size)
            for ((slot, mask) in masks) {
                ensureActive(); require(isMaskSlot(slot) && mask is EditorBitmapArtwork)
                text(slot)
                val encoded = EditorArtworkCodec.encode(mask)
                budget.bytes(encoded.size); output.writeInt(encoded.size); output.write(encoded)
            }
            result()
        }
    }

    private suspend fun decodeMasks(bytes: ByteArray): Map<String, EditorArtworkSnapshot> {
        val owner = currentCoroutineContext()
        return try {
            EditorArtworkReader(bytes) { owner.ensureActive() }.run {
                require(input.readInt() == MASK_MAGIC && input.readInt() == MASK_VERSION) { "Unsupported editor masks format" }
                val count = count(MAX_MASKS, 12)
                require(count > 0) { "Empty editor masks artifact" }
                val masks = linkedMapOf<String, EditorArtworkSnapshot>()
                repeat(count) {
                    ensureActive()
                    val slot = text()
                    require(isMaskSlot(slot) && slot !in masks) { "Invalid or duplicate editor mask slot" }
                    val mask = EditorArtworkCodec.decode(blob().copyBytes())
                    require(mask is EditorBitmapArtwork) { "Editor mask is not a bitmap" }
                    masks[slot] = mask
                }
                finished(); masks
            }
        } catch (error: IOException) { throw IllegalArgumentException("Invalid editor masks artifact", error) }
    }

    private suspend fun encodeSession(value: EditorCompleteSnapshot, controls: ByteArray): ByteArray {
        val owner = currentCoroutineContext()
        return EditorArtworkWriter { owner.ensureActive() }.run {
            output.writeInt(MAGIC); output.writeInt(VERSION)
            text(MessageDigest.getInstance("SHA-256").digest(controls).toLowerHex())
            output.writeLong(value.serial); text(value.origin.name); boolean(value.initialized); boolean(value.canRestore)
            boolean(value.vectorBaked); boolean(value.restoredSourceBaked); boolean(value.modifierRestoreFailed)
            with(value.original) {
                text(appName); text(originalName); optionalText(base); optionalText(rendered); optionalText(recipe, MAX_RECIPE_BYTES); inputState(input)
                boolean(calendar.enabled); optionalText(calendar.prefix); optionalText(calendar.packName)
                optionalText(sourcePack); optionalText(sourceUrl); boolean(fallback); boolean(refreshMade); boolean(custom); boolean(legacy)
            }
            optionalText(value.raw); optionalText(value.rendered); optionalText(value.recipe, MAX_RECIPE_BYTES)
            generation(value.requested); boolean(value.renderedOptions != null); value.renderedOptions?.let(::generation)
            text(value.pipeline.name); boolean(value.rawBaked); inputState(value.input); optionalText(value.restoredSource); inputState(value.restoredExisting)
            boolean(value.replay != null)
            value.replay?.let { inputState(it.input); generation(it.baseline); optionalStyle(it.plate) }
            output.writeInt(value.selections.size)
            value.selections.forEach { output.writeInt(it.resourceId); text(it.pack); text(it.artwork) }
            optionalText(value.uploadPath); optionalText(value.onlinePath)
            output.writeInt(value.resources.size)
            value.resources.forEach { text(it.kind.name); text(it.selection); text(it.artifact); text(it.sha256) }
            result()
        }
    }

    private suspend fun decodeSession(bytes: ByteArray, controlsBytes: ByteArray, controls: EditorControlsSnapshot, vector: EditorVectorWorkingState): EditorCompleteSnapshot {
        val owner = currentCoroutineContext()
        return try { EditorArtworkReader(bytes) { owner.ensureActive() }.run {
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported complete editor capture" }
            require(text() == MessageDigest.getInstance("SHA-256").digest(controlsBytes).toLowerHex()) { "Completed frame/control digest mismatch" }
            val serial = input.readLong(); val origin = enumValueOf<EditorDraftOrigin>(text())
            val initialized = boolean(); val canRestore = boolean(); val vectorBaked = boolean(); val restoredBaked = boolean(); val failed = boolean()
            val original = EditorOriginalSnapshot(text(), text(), optionalText(), optionalText(), optionalText(MAX_RECIPE_BYTES), inputState(),
                EditorCalendarControls(boolean(), optionalText(), optionalText()), optionalText(), optionalText(), boolean(), boolean(), boolean(), boolean())
            val raw = optionalText(); val rendered = optionalText(); val recipe = optionalText(MAX_RECIPE_BYTES); val requested = generation()
            val renderedOptions = if (boolean()) generation() else null
            val pipeline = enumValueOf<ModifierInputPipeline>(text()); val baked = boolean(); val retained = inputState(); val restored = optionalText(); val existing = inputState()
            val replay = if (boolean()) EditorReplaySnapshot(requireNotNull(inputState()), generation(), optionalStyle()) else null
            val selections = List(count(1, 12)) { EditorSourceSelection(input.readInt(), text(), text()) }
            val upload = optionalText(); val online = optionalText()
            val resources = List(count(2, 16)) { EditorResourceReference(enumValueOf<EditorDraftResourceKind>(text()), text(), text(), text()) }
            EditorCompleteSnapshot(controls, vector, serial, origin, initialized, canRestore, vectorBaked, restoredBaked, failed,
                original, raw, rendered, recipe, requested, renderedOptions, pipeline, baked, retained, restored, existing, replay, selections, upload, online, resources)
                .also { finished() }
        }
        } catch (error: IOException) { throw IllegalArgumentException("Invalid complete editor capture", error) }
    }

    private fun EditorArtworkWriter.inputState(value: EditorInputSnapshot?) {
        boolean(value != null)
        value?.let { text(it.artwork); generation(it.generation); boolean(it.baked); text(it.origin.name); text(it.pipeline.name) }
    }
    private fun EditorArtworkReader.inputState(): EditorInputSnapshot? = if (boolean()) {
        EditorInputSnapshot(text(), generation(), boolean(), enumValueOf<ModifierInputOrigin>(text()), enumValueOf<ModifierInputPipeline>(text()))
    } else null
}
