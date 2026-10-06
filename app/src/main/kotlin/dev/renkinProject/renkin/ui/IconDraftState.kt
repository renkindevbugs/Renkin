package dev.renkinProject.renkin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.modifierInputForResult
import dev.renkinProject.renkin.icon.creator.hasIconAdjustments
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

    var origin by mutableStateOf(IconOrigin.CREATE)

    var generating by mutableStateOf(false)
        private set
    private var activeGenerations = 0
    private var createGeneration = 0
    private var uploadGeneration = 0
    private var vectorGeneration = 0

    // Keep the existing icon on the first pass — only regenerate once the user actually
    // changes a source, modifier or selects an icon.
    private var initialized = false
    private var completedCreate: CreateRequest? = null

    private data class CreateRequest(
        val app: PackageInfoStruct,
        val base: IconPackDrawable?,
        val options: GenerationOptions,
        val custom: ResourceDrawable?
    )

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
        invalidateGenerations()
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
        invalidateGenerations()
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
        uploadGeneration++
        uploadBase = null
        uploadIcon = null
        uploadRecipe = null
        uploadInput = null
    }

    fun selectVector(icon: IconPackDrawable) {
        invalidateGenerations()
        createIcon = null
        uploadBase = null
        uploadIcon = null
        vectorIcon = icon
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
        createGeneration++
        uploadGeneration++
        vectorGeneration++
        completedCreate = null
    }

    suspend fun regenerateCreate(
        builder: IconPreviewBuilder,
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIconList: List<ResourceDrawable>
    ) {
        if (origin != IconOrigin.CREATE) return
        val custom = customIconList.firstOrNull()
        val request = CreateRequest(app, app.baseIcon ?: app.createdIcon, options, custom)
        if (!initialized) {
            initialized = true
            completedCreate = request
            return
        }
        // A recreated composition must not reinterpret the untouched saved icon as a new edit.
        // Record only completed renders, so a cancelled effect can retry with the same inputs.
        if (request == completedCreate) {
            createGeneration++
            return
        }
        // previewIcon / applyModifier hop to Dispatchers.Default internally, so this no
        // longer blocks the main thread; show the spinner for the duration.
        val generation = ++createGeneration
        val generated = trackGeneration {
            when {
                custom != null -> builder.previewIcon(app, options, custom)
                // Icon-pack source with no new pick: apply the modifier to the already saved icon
                // rather than pulling a fresh one from the first pack (which would swap the icon
                // out from under the user). Null until a tap if none.
                options.source.primarySource == Source.ICON_PACK ->
                    (app.baseIcon ?: app.createdIcon)?.let { builder.applyModifier(it, options) }
                else -> builder.previewIcon(app, options, null)
            }
        }
        if (generation == createGeneration) {
            createIcon = generated
            createRecipe = if (generated != null) StoredIconModifierRecipe.fromRenderedOptions(options) else null
            // Only this branch applies the complete local stage to a known input. A newly
            // generated source needs its own pre-modifier capture before it can be replayed.
            createInput = if (generated != null && custom == null && options.source.primarySource == Source.ICON_PACK) {
                request.base?.let { modifierInputForResult(it, options, ModifierInputOrigin.EXISTING_ICON) }
            } else null
            completedCreate = request
        }
    }

    suspend fun regenerateVector(builder: IconPreviewBuilder, options: GenerationOptions) {
        if (origin != IconOrigin.VECTOR) return
        val base = vectorIcon
        val generation = ++vectorGeneration
        val generated = when {
            base == null -> null
            // Adjustments without an image edit still go through applyModifier.
            options.modifiers.effect.imageEdit == ImageEdit.NONE && !options.hasIconAdjustments() -> base
            else -> trackGeneration { builder.applyModifier(base, options) }
        }
        if (generation == vectorGeneration) {
            modifiedVector = generated
            vectorRecipe = if (generated != null) StoredIconModifierRecipe.fromRenderedOptions(options) else null
            vectorInput = if (generated != null && base != null) {
                modifierInputForResult(base, options, ModifierInputOrigin.VECTOR)
            } else null
        }
    }

    suspend fun regenerateUpload(builder: IconPreviewBuilder, options: GenerationOptions) {
        if (origin != IconOrigin.UPLOAD) return
        val base = uploadBase
        val generation = ++uploadGeneration
        val generated = if (base == null) null else trackGeneration {
            builder.applyModifier(base, options)
        }
        if (generation == uploadGeneration) {
            uploadIcon = generated
            uploadRecipe = if (generated != null) StoredIconModifierRecipe.fromRenderedOptions(options) else null
            uploadInput = if (generated != null && base != null) {
                modifierInputForResult(base, options, ModifierInputOrigin.UPLOAD)
            } else null
        }
    }
}
