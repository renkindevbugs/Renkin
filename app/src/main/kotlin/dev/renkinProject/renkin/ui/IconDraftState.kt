package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.editor.EditorDraftOrigin
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconGenerationResult
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.ModifierInputPipeline
import dev.renkinProject.renkin.icon.creator.RetainedModifierStage
import dev.renkinProject.renkin.icon.creator.modifierInputForResult
import dev.renkinProject.renkin.icon.creator.hasIconAdjustments
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import dev.renkinProject.renkin.packages.PackageInfoStruct

/**
 * Holds the draft icon being built in the options dialog and the logic that (re)generates
 * its preview from the chosen options. Every field is plain Compose state — the drafts hold
 * live, non-Parcelable [IconPackDrawable]s retained by the editor session. The dialog
 * feeds user input in through the `regenerate*` calls and reads [iconToConfirm] / [hasIcon]
 * / [generating] back out, instead of carrying a dozen loose `remember`s plus the generation
 * effects inline.
 */
internal class IconDraftState(
    initialIcon: IconPackDrawable?,
    initialRecipe: String? = null,
    initialInput: IconModifierInput? = null
) {
    // Icon from the Create tab (pack pick / text / app-icon source). Starts as the icon the
    // app already has so it stays visible (e.g. when only the modifier is being changed).
    var createIcon by mutableStateOf(initialIcon)
        private set

    // Raw uploaded icon (zoom/adaptive applied) before the shared modifier; the modifier is
    // applied here so it previews live even from the Modifier tab.
    var uploadBase by mutableStateOf<IconPackDrawable?>(null)
        private set
    private var uploadIcon by mutableStateOf<IconPackDrawable?>(null)

    // Hand-edited vector and the same vector with the shared modifier applied.
    var vectorIcon by mutableStateOf<IconPackDrawable?>(null)
        private set
    private var modifiedVector by mutableStateOf<IconPackDrawable?>(null)

    private var createRecipe by mutableStateOf(initialRecipe.takeIf { initialIcon != null })
    private var uploadRecipe by mutableStateOf<String?>(null)
    private var vectorRecipe by mutableStateOf<String?>(null)
    private var createInput by mutableStateOf(initialInput.takeIf { initialIcon != null })
    private var uploadInput by mutableStateOf<IconModifierInput?>(null)
    private var vectorInput by mutableStateOf<IconModifierInput?>(null)
    private var vectorContainsBakedModifierEdits = false
    // Source switching may end replay while its controls still describe this raw input.
    private var restoredExistingInput: VerifiedModifierInput? = null
    internal var recoveredCreateStage by mutableStateOf<RetainedModifierStage?>(null)
        private set

    var origin by mutableStateOf(IconOrigin.CREATE)

    var generating by mutableStateOf(false)
        private set
    private var activeGenerations = 0
    private var createGeneration = 0
    private var uploadGeneration = 0
    private var vectorGeneration = 0
    internal var sourceRevision by mutableLongStateOf(0L)
        private set

    // Keep the existing icon on the first pass — only regenerate once the user actually
    // changes a source, modifier or selects an icon.
    private var initialized = false
    private var completedCreate: CreateRequest? = null
    private var completedFrame by mutableStateOf<CompletedFrame?>(null)
    private var initialFrame by mutableStateOf<InitialFrame?>(null)
    private var frameSerial = 0L
    private data class InitialFrame(val serial: Long, val witness: EditorRenderWitness?) {
        val content = witness?.autosaveContent()
    }
    private data class CompletedFrame(
        val witness: EditorRenderWitness?, val serial: Long,
        val requested: GenerationOptions, val rendered: GenerationOptions?,
        val input: IconPackDrawable?, val pipeline: ModifierInputPipeline, val baked: Boolean
    ) {
        val content = witness?.autosaveContent()
    }

    internal data class CaptureState(
        val serial: Long, val origin: IconOrigin, val initialized: Boolean, val canRestore: Boolean,
        val vectorContainsBakedModifierEdits: Boolean, val recipe: String?, val input: IconModifierInput?,
        val restoredExisting: VerifiedModifierInput?, val replay: VerifiedModifierInput?,
        val requested: GenerationOptions, val rendered: GenerationOptions?,
        val raw: IconPackDrawable?, val artwork: IconPackDrawable?, val pipeline: ModifierInputPipeline,
        val rawContainsBakedModifierEdits: Boolean
    )

    internal fun captureState(witness: EditorRenderWitness): CaptureState {
        val frame = completedFrame ?: throw EditorCapturePendingException()
        if (generating || replayRenderFailed || frame.witness != witness || iconToConfirm == null) throw EditorCapturePendingException()
        return CaptureState(frame.serial, origin, initialized, canRestore, vectorContainsBakedModifierEdits,
            modifierRecipeToConfirm, modifierInputToConfirm, restoredExistingInput, replay,
            frame.requested, frame.rendered, frame.input, iconToConfirm, frame.pipeline, frame.baked)
    }

    internal fun canConfirm(witness: EditorRenderWitness?): Boolean {
        val frame = completedFrame ?: return false
        return !generating && !replayRenderFailed && (witness == null || frame.witness == witness)
    }

    internal val completedFrameSerial: Long? get() = completedFrame?.serial
    internal val completedFrameWitness: EditorRenderWitness? get() = completedFrame?.witness
    internal val completedAutosaveContent: EditorAutosaveContent? get() = completedFrame?.content
    internal val initialFrameSerial: Long? get() = initialFrame?.serial
    internal val initialFrameWitness: EditorRenderWitness? get() = initialFrame?.witness
    internal val initialAutosaveContent: EditorAutosaveContent? get() = initialFrame?.content
    internal val unchangedFromOpening: Boolean get() {
        val initial = initialFrame ?: return false
        val frame = completedFrame ?: return false
        // Composition recreation may republish the same frame with a new serial.
        return frame.serial == initial.serial || (frame.content != null && frame.content == initial.content)
    }

    internal fun acknowledgeWitness(
        witness: EditorRenderWitness?, app: PackageInfoStruct,
        options: GenerationOptions, customIconList: List<ResourceDrawable>
    ) {
        val frame = completedFrame ?: return
        if (generating || replayRenderFailed || frame.requested != options || frame.witness == witness) return
        val sameSource = when (origin) {
            IconOrigin.CREATE -> completedCreate == createRequest(app, options, customIconList)
            IconOrigin.UPLOAD -> frame.input === uploadBase
            IconOrigin.VECTOR -> frame.input === vectorIcon
        }
        if (sameSource) completedFrame = frame.copy(witness = witness, serial = ++frameSerial)
    }

    private fun complete(
        witness: EditorRenderWitness?, requested: GenerationOptions, rendered: GenerationOptions?,
        input: IconPackDrawable?, pipeline: ModifierInputPipeline = ModifierInputPipeline.SHARED,
        containsBakedModifierEdits: Boolean = false
    ) {
        completedFrame = CompletedFrame(witness, ++frameSerial, requested, rendered, input, pipeline, containsBakedModifierEdits)
    }

    @MainThread
    internal fun restoreDraft(
        bound: BoundEditorRecovery, app: PackageInfoStruct,
        selections: List<ResourceDrawable>, witness: () -> EditorRenderWitness
    ) {
        check(completedFrame == null && activeGenerations == 0 && !initialized)
        val value = bound.capture.snapshot
        val raw = bound.artwork.getValue(requireNotNull(value.raw))
        val rendered = bound.artwork.getValue(requireNotNull(value.rendered))
        restoredExistingInput = bound.restoredExisting
        recoveredCreateStage = bound.createStage
        replay = bound.replay
        canRestore = value.canRestore
        initialized = value.initialized
        vectorContainsBakedModifierEdits = value.vectorBaked
        createIcon = null
        when (value.origin) {
            EditorDraftOrigin.CREATE -> {
                origin = IconOrigin.CREATE
                createIcon = rendered
                createRecipe = value.recipe
                createInput = bound.input
                completedCreate = createRequest(app, bound.requested, selections)
            }
            EditorDraftOrigin.UPLOAD -> {
                origin = IconOrigin.UPLOAD
                uploadBase = raw
                uploadIcon = rendered
                uploadRecipe = value.recipe
                uploadInput = bound.input
                createRecipe = null
                createInput = null
            }
            EditorDraftOrigin.VECTOR -> {
                origin = IconOrigin.VECTOR
                vectorIcon = raw
                modifiedVector = rendered
                vectorRecipe = value.recipe
                vectorInput = bound.input
                createRecipe = null
                createInput = null
            }
        }
        complete(witness(), bound.requested, bound.renderedOptions, raw, value.pipeline, value.rawBaked)
        initialFrame = InitialFrame(requireNotNull(completedFrame).serial, completedFrame?.witness)
    }
    var canRestore by mutableStateOf(initialInput != null && initialIcon != null)
        private set
    var replay by mutableStateOf<VerifiedModifierInput?>(null)
        private set
    var replayRenderFailed by mutableStateOf(false)
        private set

    fun restore(verified: VerifiedModifierInput) {
        invalidateGenerations()
        recoveredCreateStage = null
        replay = verified
        restoredExistingInput = verified
        replayRenderFailed = false
        createInput = verified.retained
        createRecipe = StoredIconModifierRecipe.fromRenderedOptions(verified.retained.options)
        initialized = false
        canRestore = false
    }

    fun selectNewSource() {
        recoveredCreateStage = null
        replay = null
        replayRenderFailed = false
        canRestore = false
        invalidateGenerations()
    }

    private data class CreateRequest(
        val app: PackageInfoStruct,
        val base: IconPackDrawable?,
        val options: GenerationOptions,
        val custom: ResourceDrawable?
    )

    private fun createRequest(app: PackageInfoStruct, options: GenerationOptions, customIconList: List<ResourceDrawable>) =
        CreateRequest(app, restoredExistingInput?.input ?: app.baseIcon ?: app.createdIcon, options, customIconList.firstOrNull())

    val hasIcon: Boolean get() = createIcon != null || uploadBase != null || vectorIcon != null

    /**
     * The icon Confirm would store. It follows whichever source produced it, not the open
     * tab, so visiting the Modifier tab never silently drops an upload/vector.
     */
    val iconToConfirm: IconPackDrawable? get() = when (origin) {
        IconOrigin.UPLOAD -> uploadIcon
        IconOrigin.VECTOR -> modifiedVector
        IconOrigin.CREATE -> createIcon
    }

    // Publish recipe and artwork from the same completed render, even while a newer job runs.
    val modifierRecipeToConfirm: String? get() = when (origin) {
        IconOrigin.UPLOAD -> uploadRecipe
        IconOrigin.VECTOR -> vectorRecipe
        IconOrigin.CREATE -> createRecipe
    }

    val modifierInputToConfirm: IconModifierInput? get() = when (origin) {
        IconOrigin.UPLOAD -> uploadInput
        IconOrigin.VECTOR -> vectorInput
        IconOrigin.CREATE -> createInput
    }

    /**
     * Activates the Create pipeline and drops drafts owned by the other source tabs.
     * The caller is responsible for clearing their UI-only selection state.
     */
    fun selectCreate() {
        selectNewSource()
        uploadBase = null
        uploadIcon = null
        vectorIcon = null
        modifiedVector = null
        uploadRecipe = null
        vectorRecipe = null
        uploadInput = null
        vectorInput = null
        origin = IconOrigin.CREATE
    }

    fun selectUpload(icon: IconPackDrawable) {
        selectNewSource()
        createIcon = null
        vectorIcon = null
        modifiedVector = null
        uploadBase = icon
        uploadIcon = null
        createRecipe = null
        uploadRecipe = null
        vectorRecipe = null
        createInput = null
        uploadInput = null
        vectorInput = null
        origin = IconOrigin.UPLOAD
    }

    fun clearUpload() {
        if (origin == IconOrigin.UPLOAD) {
            completedFrame = null
            sourceRevision++
        }
        uploadGeneration++
        uploadBase = null
        uploadIcon = null
        uploadRecipe = null
        uploadInput = null
    }

    fun selectVector(icon: IconPackDrawable, containsBakedModifierEdits: Boolean = false) {
        selectNewSource()
        createIcon = null
        uploadBase = null
        uploadIcon = null
        vectorIcon = icon
        vectorContainsBakedModifierEdits = containsBakedModifierEdits
        modifiedVector = null
        createRecipe = null
        uploadRecipe = null
        vectorRecipe = null
        createInput = null
        uploadInput = null
        vectorInput = null
        origin = IconOrigin.VECTOR
    }

    fun clearVector() {
        if (origin == IconOrigin.VECTOR) {
            completedFrame = null
            sourceRevision++
        }
        vectorGeneration++
        vectorIcon = null
        modifiedVector = null
        vectorRecipe = null
        vectorInput = null
    }

    /** Keeps the shared loading state correct across overlapping and cancelled effects. */
    private suspend fun <T> trackGeneration(block: suspend () -> T): T {
        activeGenerations++
        generating = true
        return try {
            block()
        } finally {
            activeGenerations--
            generating = activeGenerations > 0
        }
    }

    private fun invalidateGenerations() {
        sourceRevision++
        createGeneration++
        uploadGeneration++
        vectorGeneration++
        completedCreate = null
        completedFrame = null
    }

    suspend fun regenerateCreate(
        builder: IconPreviewBuilder,
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIconList: List<ResourceDrawable>,
        witness: EditorRenderWitness? = null,
        isCurrent: () -> Boolean = { true },
        renderSource: suspend (GenerationOptions, ResourceDrawable?) -> IconGenerationResult = { settings, custom ->
            builder.previewIconWithInput(app, settings, custom)
        },
        renderReplay: suspend (VerifiedModifierInput, GenerationOptions) -> IconGenerationResult = { input, settings ->
            input.render(settings)
        }
    ) {
        if (origin != IconOrigin.CREATE || !isCurrent()) return
        val custom = customIconList.firstOrNull()
        val existing = restoredExistingInput
        val request = createRequest(app, options, customIconList)
        if (!initialized) {
            initialized = true
            completedCreate = request
            complete(witness, options, replay?.retained?.options, request.base,
                replay?.retained?.pipeline ?: ModifierInputPipeline.SHARED,
                existing?.retained?.containsBakedModifierEdits ?: (request.base != null))
            if (initialFrame == null && sourceRevision == 0L) {
                initialFrame = InitialFrame(requireNotNull(completedFrame).serial, witness)
            }
            return
        }
        // A recreated composition must not reinterpret the untouched saved icon as a new edit.
        // Record only completed renders, so a cancelled effect can retry with the same inputs.
        if (request == completedCreate) {
            replayRenderFailed = false
            createGeneration++
            completedFrame?.let { complete(witness, options, it.rendered, it.input, it.pipeline, it.baked) }
            return
        }
        // previewIcon / applyModifier hop to Dispatchers.Default internally, so this no
        // longer blocks the main thread; show the spinner for the duration.
        val generation = ++createGeneration
        canRestore = false
        val retainedReplay = replay
        val retainedStage = recoveredCreateStage
        replayRenderFailed = false
        val generated = trackGeneration {
            when {
                retainedReplay != null -> renderReplay(retainedReplay, options)
                retainedStage != null -> retainedStage.render(options)
                custom != null -> renderSource(options, custom)
                // Icon-pack source with no new pick: apply the modifier to the already saved icon
                // rather than pulling a fresh one from the first pack (which would swap the icon
                // out from under the user). Null until a tap if none.
                options.source.primarySource == Source.ICON_PACK ->
                    IconGenerationResult(request.base?.let { builder.applyModifier(it, options) })
                else -> renderSource(options, null)
            }
        }
        if (generation == createGeneration && isCurrent()) {
            if ((retainedReplay != null || retainedStage != null) && generated.icon == null) {
                replayRenderFailed = true
                return
            }
            createIcon = generated.icon
            createRecipe = if (generated.icon != null) {
                StoredIconModifierRecipe.fromRenderedOptions(generated.renderedOptions ?: options)
            } else null
            createInput = if (retainedReplay == null && retainedStage == null && generated.icon != null && custom == null && options.source.primarySource == Source.ICON_PACK) {
                request.base?.let {
                    modifierInputForResult(it, options, ModifierInputOrigin.EXISTING_ICON,
                        containsBakedModifierEdits = existing?.retained?.containsBakedModifierEdits ?: true)
                }
            } else generated.modifierInput.takeIf { generated.icon != null }
            completedCreate = request
            complete(witness, options, generated.renderedOptions ?: options,
                generated.draftInput ?: generated.modifierInput?.liveInput ?:
                    request.base.takeIf { custom == null && options.source.primarySource == Source.ICON_PACK },
                generated.draftPipeline,
                retainedReplay?.retained?.containsBakedModifierEdits ?:
                    retainedStage?.containsBakedModifierEdits ?:
                    if (custom == null && options.source.primarySource == Source.ICON_PACK) {
                        existing?.retained?.containsBakedModifierEdits ?: true
                    } else false)
        }
    }

    suspend fun regenerateVector(
        builder: IconPreviewBuilder, options: GenerationOptions, witness: EditorRenderWitness? = null,
        isCurrent: () -> Boolean = { true }
    ) {
        if (origin != IconOrigin.VECTOR || !isCurrent()) return
        val base = vectorIcon
        val containsBakedModifierEdits = vectorContainsBakedModifierEdits
        completedFrame?.takeIf { it.requested == options && it.input === base }?.let {
            vectorGeneration++
            complete(witness, options, it.rendered, base, it.pipeline, it.baked)
            return
        }
        val generation = ++vectorGeneration
        val generated = when {
            base == null -> null
            // Adjustments without an image edit still go through applyModifier.
            options.modifiers.effect.imageEdit == ImageEdit.NONE && !options.hasIconAdjustments() -> base
            else -> trackGeneration { builder.applyModifier(base, options) }
        }
        if (generation == vectorGeneration && isCurrent()) {
            modifiedVector = generated
            vectorRecipe = if (generated != null) StoredIconModifierRecipe.fromRenderedOptions(options) else null
            vectorInput = if (generated != null && base != null) {
                modifierInputForResult(base, options, ModifierInputOrigin.VECTOR,
                    containsBakedModifierEdits = containsBakedModifierEdits)
            } else null
            complete(witness, options, options, base, containsBakedModifierEdits = containsBakedModifierEdits)
        }
    }

    suspend fun regenerateUpload(
        builder: IconPreviewBuilder, options: GenerationOptions, witness: EditorRenderWitness? = null,
        isCurrent: () -> Boolean = { true }
    ) {
        if (origin != IconOrigin.UPLOAD || !isCurrent()) return
        val base = uploadBase
        completedFrame?.takeIf { it.requested == options && it.input === base }?.let {
            uploadGeneration++
            complete(witness, options, it.rendered, base, it.pipeline, it.baked)
            return
        }
        val generation = ++uploadGeneration
        val generated = if (base == null) null else trackGeneration {
            builder.applyModifier(base, options)
        }
        if (generation == uploadGeneration && isCurrent()) {
            uploadIcon = generated
            uploadRecipe = if (generated != null) StoredIconModifierRecipe.fromRenderedOptions(options) else null
            uploadInput = if (generated != null && base != null) {
                modifierInputForResult(base, options, ModifierInputOrigin.UPLOAD)
            } else null
            complete(witness, options, options, base)
        }
    }
}
