@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import dev.renkinProject.renkin.ui.theme.DialogShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.OnlineImageImport
import android.graphics.Bitmap
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.extension.calendarPrefixOrNull
import dev.renkinProject.renkin.extension.prettyDrawableName
import dev.renkinProject.renkin.extension.toInt
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextFontKey
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.icon.creator.TextCase
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.haveMonochrome
import dev.renkinProject.renkin.drawable.isAdaptiveIconDrawable
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.icon.creator.withExport
import dev.renkinProject.renkin.icon.creator.withEffect
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.MaterialYouPackModifier
import dev.renkinProject.renkin.icon.creator.hasIconAdjustments
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.ModifierStack

internal enum class IconOrigin { CREATE, UPLOAD, VECTOR }

/** Source-pack attribution for the draft that Apply will persist. */
internal fun confirmedSourcePack(
    origin: IconOrigin,
    source: Source,
    pickedPack: String?,
    existingPack: String?
): String? = when {
    origin != IconOrigin.CREATE || source != Source.ICON_PACK -> null
    !pickedPack.isNullOrEmpty() -> pickedPack
    else -> existingPack?.takeIf { it.isNotEmpty() }
}

/**
 * How fully the comparison labels should show given a list's top position: 1 at the very top,
 * fading to 0 over the first 120px of scroll (and 0 once past the first item).
 */
private fun topFraction(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Float =
    if (firstVisibleItemIndex > 0) 0f
    else (1f - firstVisibleItemScrollOffset / 120f).coerceIn(0f, 1f)

/**
 * Holds the draft icon being built in the options dialog and the logic that (re)generates
 * its preview from the chosen options. Every field is plain Compose state — the drafts hold
 * live, non-Parcelable [IconPackDrawable]s, so they can't be saveable anyway. The dialog
 * feeds user input in through the `regenerate*` calls and reads [iconToConfirm] / [hasIcon]
 * / [generating] back out, instead of carrying a dozen loose `remember`s plus the generation
 * effects inline.
 */
internal class IconDraftState(initialIcon: IconPackDrawable?) {
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
        origin = IconOrigin.CREATE
    }

    fun selectUpload(icon: IconPackDrawable) {
        invalidateGenerations()
        createIcon = null
        vectorIcon = null
        modifiedVector = null
        uploadBase = icon
        uploadIcon = null
        origin = IconOrigin.UPLOAD
    }

    fun clearUpload() {
        uploadGeneration++
        uploadBase = null
        uploadIcon = null
    }

    fun selectVector(icon: IconPackDrawable) {
        invalidateGenerations()
        createIcon = null
        uploadBase = null
        uploadIcon = null
        vectorIcon = icon
        modifiedVector = null
        origin = IconOrigin.VECTOR
    }

    fun clearVector() {
        vectorGeneration++
        vectorIcon = null
        modifiedVector = null
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
    }

    suspend fun regenerateCreate(
        builder: IconPreviewBuilder,
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIconList: List<ResourceDrawable>
    ) {
        if (!initialized) {
            initialized = true
            return
        }
        val custom = customIconList.firstOrNull()
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
        if (generation == createGeneration) createIcon = generated
    }

    suspend fun regenerateVector(builder: IconPreviewBuilder, options: GenerationOptions) {
        val base = vectorIcon
        val generation = ++vectorGeneration
        val generated = when {
            base == null -> null
            // Adjustments without an image edit still go through applyModifier.
            options.modifiers.effect.imageEdit == ImageEdit.NONE && !options.hasIconAdjustments() -> base
            else -> trackGeneration { builder.applyModifier(base, options) }
        }
        if (generation == vectorGeneration) modifiedVector = generated
    }

    suspend fun regenerateUpload(builder: IconPreviewBuilder, options: GenerationOptions) {
        val base = uploadBase
        val generation = ++uploadGeneration
        val generated = if (base == null) null else trackGeneration {
            builder.applyModifier(base, options)
        }
        if (generation == uploadGeneration) uploadIcon = generated
    }
}

@Composable
fun OptionsDialog(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    themed: Boolean,
    onConfirm: (icon: IconPackDrawable?, calendarEnabled: Boolean, calendarPrefix: String?, calendarPackName: String?, sourcePackName: String?, sourceUrl: String?) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf(Source.ICON_PACK) }
    var imageEdit by rememberSaveable { mutableStateOf(ImageEdit.NONE) }
    var textType by rememberSaveable { mutableStateOf(TextType.FULL_NAME) }
    var customText by rememberSaveable { mutableStateOf(app.appName) }
    var textCase by rememberSaveable { mutableStateOf(TextCase.AS_IS) }
    val globalFontPath = getPreferences().getStringValue(TextFontKey)
    var textFontPath by rememberSaveable(globalFontPath) { mutableStateOf(globalFontPath) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var applicationIconVariant by rememberSaveable { mutableStateOf(ApplicationIconVariant.DEFAULT) }
    var useFullApplicationIcon by rememberSaveable { mutableStateOf(false) }
    var invertMonochrome by rememberSaveable { mutableStateOf(false) }
    var materialYouScheme by rememberSaveable { mutableIntStateOf(0) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    var customBgColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.Black) }
    // The Custom scheme's full styles. The flat colours above stay their first stop, because the
    // pack's own Material You layers and the vector export can only take one colour.
    var materialYouForegroundStyle by rememberSaveable(stateSaver = colorizerStyleSaver()) {
        mutableStateOf(ColorizerStyle(firstColor = iconColor.toArgb()))
    }
    var materialYouBackgroundStyle by rememberSaveable(stateSaver = colorizerStyleSaver()) {
        mutableStateOf(ColorizerStyle(firstColor = customBgColor.toArgb()))
    }
    var iconPack by rememberSaveable { mutableStateOf(iconPacks.firstOrNull()?.packageName ?: "") }
    // remember (not rememberSaveable): ResourceDrawable holds a live Drawable that isn't
    // Parcelable, so saving the list on stop crashes.
    var customIconList by remember { mutableStateOf<List<ResourceDrawable>>(listOf()) }
    // The Create tab's icon search. Hoisted here (not inside CreateTab) so it survives leaving
    // and returning to the tab; it starts at the app's non-localized name and resets per dialog
    // (i.e. per edit) — icon packs name drawables in English, so the localized label rarely matches.
    var createSearchQuery by rememberSaveable { mutableStateOf(app.originalName) }
    var iconSortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var packSortOrder by rememberSaveable { mutableStateOf(PackSortOrder.USAGE) }
    var createBusy by remember { mutableStateOf(false) }
    var packUsage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) { packUsage = viewModel.packUsageCounts() }
    val draft = remember { IconDraftState(app.baseIcon ?: app.createdIcon) }
    val vectorEditState = remember { VectorEditState() }
    // Attribution URL for an online icon imported "as image" (it lands in the upload draft,
    // not the vector editor) and the gallery file it was saved as. The attribution is
    // dropped as soon as the gallery selects any other picture.
    var onlineImageUrl by remember { mutableStateOf<String?>(null) }
    var onlineImagePath by remember { mutableStateOf<String?>(null) }
    var uploadSelectionVersion by rememberSaveable { mutableIntStateOf(0) }
    var showConfirmClear by remember { mutableStateOf(false) }
    val headerScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // The pack list and the single-pack grid states + which pack is expanded, hoisted so the header
    // can fade the Current/New labels by whichever list is showing — by its exact distance from the
    // very top (over the first 120px). Unlike the enter-always app bar, the labels only return when
    // scrolled fully up, not on any upward scroll mid-list.
    val iconListState = rememberLazyListState()
    val iconGridState = rememberLazyGridState()
    var expandedPack by remember { mutableStateOf<IconPack?>(null) }
    val labelExpand by remember {
        derivedStateOf {
            when {
                selectedTab != 0 || source != Source.ICON_PACK -> 1f
                expandedPack != null -> topFraction(iconGridState.firstVisibleItemIndex, iconGridState.firstVisibleItemScrollOffset)
                else -> topFraction(iconListState.firstVisibleItemIndex, iconListState.firstVisibleItemScrollOffset)
            }
        }
    }
    // The global "Add outline" preference is deliberately NOT seeded here: it applies to the
    // bulk refresh's own (hero-source) icons only, never to hand-picked ones. Per-app outline
    // stays an explicit choice in the Modifier tab.
    val adjustments = rememberSaveable(saver = AdjustmentState.Saver) { AdjustmentState() }
    val storedMaterialYouPackState = remember(app.baseIcon, app.createdIcon) {
        ((app.baseIcon ?: app.createdIcon) as? AdaptiveIconPackDrawable)?.materialYouEditState
    }
    val materialYouPackAdjustments = rememberSaveable(
        storedMaterialYouPackState,
        saver = MaterialYouPackAdjustmentState.Saver
    ) { MaterialYouPackAdjustmentState(storedMaterialYouPackState) }

    // Browsing a calendar icon must not persist its rotation choice until Apply.
    var calendarEnabled by rememberSaveable { mutableStateOf(app.calendarEnabled) }
    var calendarPrefix by remember { mutableStateOf(app.calendarPrefix) }
    var calendarPackName by remember { mutableStateOf(app.calendarPackName) }
    val calendarPackLabel = iconPacks.find { it.packageName == (calendarPackName ?: iconPack) }?.applicationName ?: ""

    /** A real selection in another source invalidates all UI state owned by Create. */
    val clearCreateSelection: () -> Unit = {
        customIconList = emptyList()
        materialYouPackAdjustments.reset()
        calendarEnabled = false
        calendarPrefix = null
        calendarPackName = null
    }

    /** Switches to Create only after the user changes a Create option or picks a Create icon. */
    val activateCreate: () -> Unit = {
        if (draft.origin != IconOrigin.CREATE) {
            draft.selectCreate()
            vectorEditState.reset()
            onlineImageUrl = null
            onlineImagePath = null
            uploadSelectionVersion++
        }
    }

    val activateUpload: (IconPackDrawable) -> Unit = { icon ->
        if (draft.origin != IconOrigin.UPLOAD) {
            clearCreateSelection()
            vectorEditState.reset()
        }
        draft.selectUpload(icon)
    }

    val activateVector: (IconPackDrawable) -> Unit = { icon ->
        if (draft.origin != IconOrigin.VECTOR) {
            clearCreateSelection()
            onlineImageUrl = null
            onlineImagePath = null
            uploadSelectionVersion++
        }
        draft.selectVector(icon)
    }

    val dialogTransition = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(dialogTransition.targetState, dialogTransition.isIdle) {
        if (!dialogTransition.targetState && dialogTransition.isIdle) onDismiss()
    }
    val startClose: () -> Unit = { dialogTransition.targetState = false }

    // The Create tab's icon-pack browser is heavy to mount, so defer it until the open
    // animation has settled — the dialog then appears instantly
    val createTabReady by remember {
        derivedStateOf { dialogTransition.isIdle && dialogTransition.currentState }
    }

    val heroBitmap = remember(app.icon) {
        runCatching { app.icon.toSafeBitmapOrNull() }.getOrNull()
    }

    // Whether the app ships an official Material You <monochrome> layer. Apps without one use
    // Renkin's labelled generated fallback instead.
    val appHasAdaptiveIcon = remember(app.icon) { app.icon.isAdaptiveIconDrawable() }
    val appHasMaterialYouIcon = remember(app.icon, appHasAdaptiveIcon) {
        appHasAdaptiveIcon && (app.icon as AdaptiveIconDrawable).haveMonochrome()
    }

    val isMaterialYouVariant = source == Source.APPLICATION_ICON &&
        applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU
    val materialYouSchemes = rememberMaterialYouSchemes()
    val selectedMaterialYouPackIcon = draft.origin == IconOrigin.CREATE &&
        source == Source.ICON_PACK &&
        (if (customIconList.isEmpty()) {
            storedMaterialYouPackState != null
        } else {
            customIconList.first().drawable.isAdaptiveIconDrawable() &&
                iconPacks.firstOrNull { it.packageName == iconPack }
                    ?.changesWithMaterialYouColors == true
        })
    val isCustomScheme = materialYouScheme >= materialYouSchemes.size
    val scheme = materialYouSchemes.getOrNull(materialYouScheme)
    // The generated approximation maps the regular artwork's light/dark roles in reverse. Swap
    // only its Custom inputs for now; official monochrome layers and wallpaper schemes stay put.
    val swapGeneratedCustomColors = isMaterialYouVariant && !appHasMaterialYouIcon && isCustomScheme
    val effectiveColor = when {
        swapGeneratedCustomColors -> customBgColor
        isMaterialYouVariant && !isCustomScheme -> scheme!!.first
        else -> iconColor
    }
    // Background only applies to the Material You variant and the shape plate; other sources
    // keep the transparent default.
    val effectiveBgColor = when {
        swapGeneratedCustomColors -> iconColor
        isMaterialYouVariant && !isCustomScheme -> scheme!!.second
        isMaterialYouVariant -> customBgColor
        adjustments.iconShape != IconShape.NONE && !adjustments.shapeCrop -> Color(adjustments.shapeStyle.firstColor)
        else -> Color.Transparent
    }
    // Styles only exist for the Custom scheme: a wallpaper scheme is two flat colours by
    // definition, and the shape plate has its own style further down.
    val effectiveForegroundStyle = when {
        !isMaterialYouVariant || !isCustomScheme -> null
        swapGeneratedCustomColors -> materialYouBackgroundStyle
        else -> materialYouForegroundStyle
    }
    val effectiveBackgroundStyle = when {
        isMaterialYouVariant && isCustomScheme && swapGeneratedCustomColors ->
            materialYouForegroundStyle
        isMaterialYouVariant && isCustomScheme -> materialYouBackgroundStyle
        // The shape plate's own style is applied by withModifierAdjustments; adding it here too
        // would just be a second place to keep in sync.
        else -> null
    }
    val selectedPackScheme = materialYouSchemes.getOrNull(
        materialYouPackAdjustments.selectedScheme
    )
    val materialYouPackForeground = when {
        !selectedMaterialYouPackIcon || materialYouPackAdjustments.selectedScheme < 0 -> null
        materialYouPackAdjustments.selectedScheme >= materialYouSchemes.size ->
            materialYouPackAdjustments.customForeground.firstColor
        else -> selectedPackScheme!!.first.toInt()
    }
    val materialYouPackBackground = when {
        !selectedMaterialYouPackIcon || materialYouPackAdjustments.selectedScheme < 0 -> null
        materialYouPackAdjustments.selectedScheme >= materialYouSchemes.size ->
            materialYouPackAdjustments.customBackground.firstColor
        else -> selectedPackScheme!!.second.toInt()
    }

    // Memoised per stroke list: the options object must only change when the strokes do,
    // or every recomposition would look like a new mask and re-trigger generation.
    val outlineEraseMask = remember(adjustments.eraseStrokes) {
        if (adjustments.eraseStrokes.isEmpty()) null else buildEraseMask(adjustments.eraseStrokes)
    }
    val backgroundBrushOperations = remember(adjustments.backgroundBrushStrokes) {
        buildBackgroundBrushOperations(adjustments.backgroundBrushStrokes)
    }
    val generatingOptions = GenerationOptions(
        source = IconSourceOptions(
            primarySource = source,
            primaryTextType = textType,
            primaryIconPack = iconPack,
            textCustom = customText,
            textCase = textCase,
            textFontPath = textFontPath,
            applicationIconVariant = applicationIconVariant,
            useFullApplicationIcon = useFullApplicationIcon,
            invertMonochrome = invertMonochrome
        ),
        export = IconExportOptions(
            vector = useVector,
            materialYou = applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU,
            themed = themed,
            foregroundStyle = effectiveForegroundStyle,
            background = ColorFill(
                color = effectiveBgColor.toInt(),
                style = effectiveBackgroundStyle
            )
        ),
        modifiers = ModifierStack(
            effect = EffectModifier(imageEdit, ColorizerStyle(firstColor = effectiveColor.toInt())),
            materialYouPack = MaterialYouPackModifier(
                foreground = materialYouPackForeground,
                background = materialYouPackBackground,
                strokeScale = if (selectedMaterialYouPackIcon) {
                    materialYouPackAdjustments.strokeScale
                } else 1f,
                selectedScheme = materialYouPackAdjustments.selectedScheme,
                customForeground = materialYouPackAdjustments.customForeground,
                customBackground = materialYouPackAdjustments.customBackground
            )
        ),
        override = true
    ).withModifierAdjustments(
        adjustments = adjustments,
        imageEdit = imageEdit,
        outlineEraseMask = outlineEraseMask,
        backgroundBrushOperations = backgroundBrushOperations
    )
    // Pack rows describe the source artwork, not its themed export. The launcher applies the
    // adaptive viewport to themed output; applying that inset directly to these flat browser
    // tiles made every candidate look half-sized until a modifier happened to flatten it.
    val browserOptions = generatingOptions
        .withExport { copy(themed = false) }
        .withModifiers { copy(materialYouPack = MaterialYouPackModifier()) }

    // The Colorize sheet previews a draft style that is NOT applied yet, so it runs the real
    // generation pipeline with the draft substituted in — anything cheaper (colouring the app's
    // current icon) would show a different icon than the one Apply produces.
    val renderPreviewWith: suspend (GenerationOptions) -> Bitmap? = { previewOptions ->
        val rendered = when (draft.origin) {
            IconOrigin.UPLOAD -> draft.uploadBase?.let { viewModel.applyModifier(it, previewOptions) }
            IconOrigin.VECTOR -> draft.vectorIcon?.let { viewModel.applyModifier(it, previewOptions) }
            IconOrigin.CREATE -> {
                val custom = customIconList.firstOrNull()
                when {
                    custom != null -> viewModel.previewIcon(app, previewOptions, custom)
                    previewOptions.source.primarySource == Source.ICON_PACK ->
                        (app.baseIcon ?: app.createdIcon)?.let {
                            viewModel.applyModifier(it, previewOptions)
                        }
                    else -> viewModel.previewIcon(app, previewOptions, null)
                }
            }
        }
        rendered?.toModifierBitmap()
    }
    val modifierPreviews = rememberModifierPreviews(
        options = generatingOptions,
        adjustments = adjustments,
        sourceKey = when (draft.origin) {
            IconOrigin.UPLOAD -> draft.uploadBase
            IconOrigin.VECTOR -> draft.vectorIcon
            IconOrigin.CREATE -> customIconList.firstOrNull()?.let {
                iconPack to it.resourceId
            } ?: (app.baseIcon ?: app.createdIcon ?: app.icon)
        },
        render = renderPreviewWith
    )

    LaunchedEffect(generatingOptions, customIconList) {
        draft.regenerateCreate(viewModel, app, generatingOptions, customIconList)
    }
    LaunchedEffect(draft.vectorIcon, generatingOptions) {
        draft.regenerateVector(viewModel, generatingOptions)
    }
    LaunchedEffect(draft.uploadBase, generatingOptions) {
        draft.regenerateUpload(viewModel, generatingOptions)
    }

    // The snackbar surface exists only for the upload gallery's Undo action; plain hints go
    // through the shared Toaster like everywhere else in the app.
    val snackbarHostState = remember { SnackbarHostState() }
    val toaster = LocalToaster.current
    val selectIconMessage = stringResource(R.string.selectIconFirst)
    val externalEditorError = stringResource(R.string.noImageEditorAvailable)
    val context = LocalContext.current
    val externalEditorScope = rememberCoroutineScope()

    LaunchedEffect(selectedTab) {
        // Leaving the icon-pack list re-expands the app bar (other tabs barely scroll).
        if (selectedTab != 0) headerScrollBehavior.state.heightOffset = 0f
        // Modifiers live only in the Modifier tab — leaving it starts the next visit clean
        if (selectedTab != 2) {
            imageEdit = ImageEdit.NONE
        }
    }

    val confirmIcon: () -> Unit = {
        // Credit the icon to a pack only when it actually came from one: the Create
        // tab's Icon Pack source. A fresh pick uses the picked pack; keeping the
        // existing icon keeps its stored source. Upload/vector/text/app-icon = none.
        val sourcePackToPersist = confirmedSourcePack(
            origin = draft.origin,
            source = source,
            pickedPack = iconPack.takeIf { customIconList.isNotEmpty() },
            existingPack = app.sourcePackName
        )
        // Online-library attribution follows how the icon was imported: a
        // confirmed vector carries the vector tab's URL, an "as image"
        // import the upload draft's; other origins have no online source.
        val sourceUrlToPersist = when (draft.origin) {
            IconOrigin.VECTOR -> vectorEditState.sourceUrl
            IconOrigin.UPLOAD -> onlineImageUrl
            else -> null
        }
        onConfirm(draft.iconToConfirm, calendarEnabled, calendarPrefix, calendarPackName, sourcePackToPersist, sourceUrlToPersist)
    }

    val adaptiveLayoutInfo = LocalAdaptiveLayoutInfo.current
    val paneLayout = horizontalPaneLayout(
        availableWidth = adaptiveLayoutInfo.windowWidth,
        preferredLeadingWidth = 320.dp,
        minimumLeadingWidth = 280.dp,
        minimumTrailingWidth = 360.dp,
        separatingVerticalHinge = adaptiveLayoutInfo.separatingVerticalHinge
    )

    Dialog(
        onDismissRequest = startClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AnimatedVisibility(
            visibleState = dialogTransition,
            enter = slideInHorizontally(
                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
            ) { it } + fadeIn(),
            exit = slideOutHorizontally(
                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
            ) { it } + fadeOut()
        ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            // No imePadding here: the keyboard should overlay the bottom tabs
            // rather than lifting them. The search field sits near the top, so it
            // stays visible above the keyboard.
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .then(
                        // A short-window Modifier tab needs every drag for its dense form;
                        // letting the header pre-consume it can leave the form immovable.
                        if (paneLayout != null || selectedTab == 2) Modifier
                        else Modifier.nestedScroll(headerScrollBehavior.nestedScrollConnection)
                    )
            ) {
                val packBrowsing = selectedTab == 0 && source == Source.ICON_PACK
                val tabContent: @Composable (PaddingValues) -> Unit = { headerPadding ->
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            (fadeIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) +
                             slideInVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { it / 8 }) togetherWith
                            (fadeOut(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) +
                             slideOutVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { -it / 8 })
                        },
                        label = "tabContent"
                    ) { tab ->
                        when (tab) {
                            0 -> CreateTab(
                                source = source,
                                contentPadding = headerPadding,
                                iconPacks = iconPacks,
                                options = browserOptions,
                                textType = textType,
                                listState = iconListState,
                                gridState = iconGridState,
                                expandedPack = expandedPack,
                                onExpandedPackChange = { expandedPack = it },
                                sortOrder = iconSortOrder,
                                packSort = packSortOrder,
                                onBusyChange = { createBusy = it },
                                packUsage = packUsage,
                                searchQuery = createSearchQuery,
                                // Component matching only while the query is the untouched default
                                // (the app's name); a custom query = pure text search.
                                componentMatch = if (createSearchQuery == app.originalName) {
                                    app.toInstalledApplication()
                                } else null,
                                onIconSelect = { res, pack, drawableName ->
                                    if (customIconList.firstOrNull()?.resourceId != res.resourceId ||
                                        iconPack != pack.packageName
                                    ) {
                                        materialYouPackAdjustments.reset()
                                    }
                                    customIconList = listOf(res)
                                    iconPack = pack.packageName
                                    activateCreate()
                                    val newPrefix = drawableName.calendarPrefixOrNull()
                                    calendarPrefix = newPrefix
                                    calendarPackName = if (newPrefix != null) pack.packageName else null
                                    // If calendar was enabled but the new icon can't rotate, disable it
                                    // (locally — like the toggle, this commits on Apply).
                                    if (newPrefix == null && calendarEnabled) {
                                        calendarEnabled = false
                                    }
                                },
                                onTextTypeChange = { textType = it; activateCreate() },
                                customText = customText,
                                onCustomTextChange = { customText = it; activateCreate() },
                                textCase = textCase,
                                onTextCaseChange = { textCase = it; activateCreate() },
                                fontPath = textFontPath,
                                onFontPathChange = { textFontPath = it; activateCreate() },
                                contentReady = createTabReady,
                                selectedResourceId = customIconList.firstOrNull()?.resourceId,
                                selectedCalendarPrefix = calendarPrefix.takeIf { calendarEnabled },
                                appHasMaterialYouIcon = appHasMaterialYouIcon,
                                appHasAdaptiveIcon = appHasAdaptiveIcon,
                                applicationIconVariant = applicationIconVariant,
                                onApplicationIconVariantChange = {
                                    applicationIconVariant = it
                                    activateCreate()
                                },
                                useFullApplicationIcon = useFullApplicationIcon,
                                onUseFullApplicationIconChange = {
                                    useFullApplicationIcon = it
                                    activateCreate()
                                },
                                invertMonochrome = invertMonochrome,
                                onInvertMonochromeChange = {
                                    invertMonochrome = it
                                    activateCreate()
                                },
                                materialYouSchemes = materialYouSchemes,
                                selectedScheme = materialYouScheme,
                                onSchemeChange = {
                                    materialYouScheme = it
                                    activateCreate()
                                },
                                customForeground = materialYouForegroundStyle,
                                customBackground = materialYouBackgroundStyle,
                                onCustomForegroundChange = {
                                    materialYouForegroundStyle = it
                                    iconColor = Color(it.firstColor)
                                    activateCreate()
                                },
                                onCustomBackgroundChange = {
                                    materialYouBackgroundStyle = it
                                    customBgColor = Color(it.firstColor)
                                    activateCreate()
                                },
                                // The generated variant swaps the Custom inputs, so each control
                                // previews through whichever field it actually feeds.
                                renderMaterialYouForeground = { style ->
                                    renderPreviewWith(
                                        if (swapGeneratedCustomColors) {
                                            generatingOptions.withExport {
                                                copy(background = ColorFill(style.firstColor, style))
                                            }
                                        } else {
                                            generatingOptions
                                                .withExport { copy(foregroundStyle = style) }
                                                .withModifiers {
                                                    withEffect {
                                                        copy(colorizer = colorizer.copy(firstColor = style.firstColor))
                                                    }
                                                }
                                        }
                                    )
                                },
                                renderMaterialYouBackground = { style ->
                                    renderPreviewWith(
                                        if (swapGeneratedCustomColors) {
                                            generatingOptions
                                                .withExport { copy(foregroundStyle = style) }
                                                .withModifiers {
                                                    withEffect {
                                                        copy(colorizer = colorizer.copy(firstColor = style.firstColor))
                                                    }
                                                }
                                        } else {
                                            generatingOptions.withExport {
                                                copy(background = ColorFill(style.firstColor, style))
                                            }
                                        }
                                    )
                                }
                            )
                            1 -> UploadOptionsTab(
                                contentPadding = headerPadding,
                                selectionVersion = uploadSelectionVersion,
                                snackbarHostState = snackbarHostState,
                                initialSelectedPath = onlineImagePath,
                                onSelection = { icon, path ->
                                    if (icon == null) {
                                        draft.clearUpload()
                                    } else {
                                        if (path != onlineImagePath) onlineImageUrl = null
                                        activateUpload(icon)
                                    }
                                }
                            )
                            2 -> ModifierOptionsTab(
                                contentPadding = headerPadding,
                                source = source,
                                imageEdit = imageEdit,
                                iconColor = iconColor,
                                useVector = useVector,
                                useMaterialYou = applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU,
                                adjustments = adjustments,
                                centerPreview = remember(draft.iconToConfirm) {
                                    draft.iconToConfirm?.toModifierBitmap()
                                },
                                previewGenerating = draft.generating,
                                sampleBitmap = heroBitmap,
                                previews = modifierPreviews,
                                materialYouPackAdjustments =
                                    materialYouPackAdjustments.takeIf { selectedMaterialYouPackIcon },
                                materialYouSchemes = materialYouSchemes,
                                onImageEditChange = { imageEdit = it },
                                onColorChange = { iconColor = it },
                                onVectorChange = { useVector = it },
                                onMaterialYouChange = {
                                    applicationIconVariant = if (it) ApplicationIconVariant.MATERIAL_YOU
                                    else ApplicationIconVariant.DEFAULT
                                },
                                onEditExternally = { toolbox ->
                                    val icon = draft.iconToConfirm
                                    if (icon == null) {
                                        toaster.show(selectIconMessage)
                                    } else {
                                        externalEditorScope.launch {
                                            val bitmap = withContext(Dispatchers.Default) {
                                                icon.toModifierBitmap()
                                            }
                                            val opened = if (toolbox) {
                                                openInImageToolbox(context, bitmap)
                                            } else {
                                                editInAnotherApp(context, bitmap)
                                            }
                                            if (!opened) toaster.show(externalEditorError)
                                        }
                                    }
                                }
                            )
                            else -> VectorOptionsTab(
                                contentPadding = headerPadding,
                                app = app,
                                state = vectorEditState,
                                onImportedImage = { imported, url ->
                                    activateUpload(BitmapIconDrawable(imported.bitmap, false))
                                    onlineImageUrl = url
                                    onlineImagePath = imported.galleryPath
                                },
                                onIconChange = {
                                    if (it == null) draft.clearVector() else activateVector(it)
                                }
                            )
                        }
                    }
                }

                val bottomSection: @Composable () -> Unit = {
                    AnimatedVisibility(visible = selectedTab == 0) {
                        SourcePills(source = source) { newSource ->
                            source = newSource
                            customIconList = listOf()
                            activateCreate()
                        }
                    }
                    HorizontalDivider()
                    OptionsBottomBar(
                        selectedTab = selectedTab,
                        modifierEnabled = draft.hasIcon,
                        onSelectTab = { selectedTab = it },
                        onModifierBlocked = {
                            toaster.show(selectIconMessage)
                        }
                    )
                }

                val preview = OptionsPreview(
                    heroBitmap = heroBitmap,
                    appName = app.appName,
                    icon = draft.iconToConfirm,
                    loading = draft.generating
                )
                val actions = OptionsPreviewActions(
                    onDismiss = startClose,
                    onClear = { showConfirmClear = true },
                    onConfirm = confirmIcon
                )
                val packBrowser = PackBrowserChrome(
                    visible = packBrowsing,
                    expanded = expandedPack != null,
                    busy = createBusy,
                    query = createSearchQuery,
                    iconSortOrder = iconSortOrder,
                    packSortOrder = packSortOrder,
                    onBack = { expandedPack = null },
                    onQueryChange = { createSearchQuery = it },
                    onIconSortChange = { iconSortOrder = it },
                    onPackSortChange = { packSortOrder = it }
                )
                val calendar = CalendarSelection(
                    visible = selectedTab == 0 && source == Source.ICON_PACK && calendarPrefix != null,
                    packName = calendarPackLabel,
                    prefix = calendarPrefix.orEmpty(),
                    enabled = calendarEnabled,
                    onToggle = { calendarEnabled = it }
                )

                if (paneLayout != null) {
                    WideOptionsLayout(
                        paneLayout = paneLayout,
                        preview = preview,
                        actions = actions,
                        packBrowser = packBrowser,
                        calendar = calendar,
                        tabContent = tabContent,
                        bottomSection = bottomSection
                    )
                } else {
                    PhoneOptionsLayout(
                        selectedTab = selectedTab,
                        preview = preview,
                        actions = actions,
                        packBrowser = packBrowser,
                        calendar = calendar,
                        headerScrollBehavior = headerScrollBehavior,
                        labelExpand = labelExpand,
                        tabContent = tabContent,
                        bottomSection = bottomSection
                    )
                }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            )
            }
        }
        }
    }

    if (showConfirmClear) {
        ConfirmClearDialog(
            onDismiss = { showConfirmClear = false },
            onIconClear = {
                showConfirmClear = false
                onIconClear()
            }
        )
    }
}

private data class OptionsPreview(
    val heroBitmap: Bitmap?,
    val appName: String,
    val icon: IconPackDrawable?,
    val loading: Boolean
)

@Composable
private fun UploadOptionsTab(
    contentPadding: PaddingValues,
    selectionVersion: Int,
    snackbarHostState: SnackbarHostState,
    initialSelectedPath: String?,
    onSelection: (IconPackDrawable?, String?) -> Unit
) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        key(selectionVersion) {
            UploadColumn(
                snackbarHostState = snackbarHostState,
                initialSelectedPath = initialSelectedPath,
                onChange = onSelection
            )
        }
    }
}

@Composable
private fun ModifierOptionsTab(
    contentPadding: PaddingValues,
    source: Source,
    imageEdit: ImageEdit,
    iconColor: Color,
    useVector: Boolean,
    useMaterialYou: Boolean,
    adjustments: AdjustmentState,
    centerPreview: Bitmap?,
    previewGenerating: Boolean,
    sampleBitmap: Bitmap?,
    previews: ModifierPreviews,
    materialYouPackAdjustments: MaterialYouPackAdjustmentState?,
    materialYouSchemes: List<Pair<Color, Color>>,
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMaterialYouChange: (Boolean) -> Unit,
    onEditExternally: (Boolean) -> Unit
) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        ModifierTab(
            source = source,
            imageEdit = imageEdit,
            iconColor = iconColor,
            useVector = useVector,
            useMaterialYou = useMaterialYou,
            adjustments = adjustments,
            centerPreview = centerPreview,
            previewGenerating = previewGenerating,
            sampleBitmap = sampleBitmap,
            previews = previews,
            materialYouPackAdjustments = materialYouPackAdjustments,
            materialYouSchemes = materialYouSchemes,
            onImageEditChange = onImageEditChange,
            onColorChange = onColorChange,
            onVectorChange = onVectorChange,
            onMaterialYouChange = onMaterialYouChange,
            onEditExternally = onEditExternally
        )
    }
}

@Composable
private fun VectorOptionsTab(
    contentPadding: PaddingValues,
    app: PackageInfoStruct,
    state: VectorEditState,
    onImportedImage: (OnlineImageImport, String) -> Unit,
    onIconChange: (IconPackDrawable?) -> Unit
) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        PrepareEditVector(
            app = app,
            state = state,
            onImportedImage = onImportedImage,
            onChange = onIconChange
        )
    }
}

private data class OptionsPreviewActions(
    val onDismiss: () -> Unit,
    val onClear: () -> Unit,
    val onConfirm: () -> Unit
)

private data class PackBrowserChrome(
    val visible: Boolean,
    val expanded: Boolean,
    val busy: Boolean,
    val query: String,
    val iconSortOrder: IconSortOrder,
    val packSortOrder: PackSortOrder,
    val onBack: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onIconSortChange: (IconSortOrder) -> Unit,
    val onPackSortChange: (PackSortOrder) -> Unit
)

private data class CalendarSelection(
    val visible: Boolean,
    val packName: String,
    val prefix: String,
    val enabled: Boolean,
    val onToggle: (Boolean) -> Unit
)

@Composable
private fun WideOptionsLayout(
    paneLayout: HorizontalPaneLayout,
    preview: OptionsPreview,
    actions: OptionsPreviewActions,
    packBrowser: PackBrowserChrome,
    calendar: CalendarSelection,
    tabContent: @Composable (PaddingValues) -> Unit,
    bottomSection: @Composable () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        EditPreviewPane(
            heroBitmap = preview.heroBitmap,
            appName = preview.appName,
            previewIcon = preview.icon,
            previewLoading = preview.loading,
            confirmEnabled = !preview.loading,
            onDismiss = actions.onDismiss,
            onClear = actions.onClear,
            onConfirm = actions.onConfirm,
            modifier = Modifier.width(paneLayout.leadingWidth),
            extraCard = if (calendar.visible) {
                {
                    CalendarCard(
                        packName = calendar.packName,
                        calendarPrefix = calendar.prefix,
                        calendarEnabled = calendar.enabled,
                        onToggle = calendar.onToggle
                    )
                }
            } else null
        )
        AdaptivePaneSeparator(paneLayout)
        Column(Modifier.width(paneLayout.trailingWidth)) {
            PackBrowserToolbar(packBrowser)
            HorizontalDivider()
            Box(Modifier.weight(1f)) { tabContent(PaddingValues(0.dp)) }
            bottomSection()
        }
    }
}

@Composable
private fun PhoneOptionsLayout(
    selectedTab: Int,
    preview: OptionsPreview,
    actions: OptionsPreviewActions,
    packBrowser: PackBrowserChrome,
    calendar: CalendarSelection,
    headerScrollBehavior: TopAppBarScrollBehavior,
    labelExpand: Float,
    tabContent: @Composable (PaddingValues) -> Unit,
    bottomSection: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        OverlayHeaderLayout(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            header = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    ComparisonHeader(
                        heroBitmap = preview.heroBitmap,
                        appName = preview.appName,
                        previewIcon = preview.icon,
                        previewLoading = preview.loading,
                        confirmEnabled = !preview.loading,
                        onDismiss = actions.onDismiss,
                        onClear = actions.onClear,
                        onConfirm = actions.onConfirm,
                        scrollBehavior = headerScrollBehavior,
                        labelExpand = labelExpand,
                        titleContent = if (packBrowser.visible) {
                            {
                                AppBarSearchField(
                                    query = packBrowser.query,
                                    onQueryChange = packBrowser.onQueryChange,
                                    placeholder = stringResource(R.string.searchIcons)
                                )
                            }
                        } else null,
                        extraActions = if (packBrowser.visible) {
                            {
                                IconSortMenuButton(
                                    sortOrder = packBrowser.iconSortOrder,
                                    onSortOrderChange = packBrowser.onIconSortChange,
                                    packSortOrder = packBrowser.packSortOrder,
                                    onPackSortOrderChange = packBrowser.onPackSortChange
                                )
                            }
                        } else null,
                        onNavigateBack = {
                            if (packBrowser.visible && packBrowser.expanded) {
                                packBrowser.onBack()
                            } else {
                                actions.onDismiss()
                            }
                        },
                        showProgress = packBrowser.visible && packBrowser.busy
                    )
                    if (selectedTab != 0) HorizontalDivider()
                    AnimatedVisibility(visible = calendar.visible) {
                        CalendarCard(
                            packName = calendar.packName,
                            calendarPrefix = calendar.prefix,
                            calendarEnabled = calendar.enabled,
                            onToggle = calendar.onToggle
                        )
                    }
                }
            }
        ) { headerPadding ->
            tabContent(headerPadding)
        }
        bottomSection()
    }
}

@Composable
private fun PackBrowserToolbar(packBrowser: PackBrowserChrome) {
    if (!packBrowser.visible) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (packBrowser.expanded) {
            IconButton(onClick = packBrowser.onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.dismiss)
                )
            }
        }
        Box(Modifier.weight(1f)) {
            AppBarSearchField(
                query = packBrowser.query,
                onQueryChange = packBrowser.onQueryChange,
                placeholder = stringResource(R.string.searchIcons)
            )
        }
        IconSortMenuButton(
            sortOrder = packBrowser.iconSortOrder,
            onSortOrderChange = packBrowser.onIconSortChange,
            packSortOrder = packBrowser.packSortOrder,
            onPackSortOrderChange = packBrowser.onPackSortChange
        )
    }
    if (packBrowser.busy) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        )
    }
}


@Composable
private fun OptionsBottomBar(
    selectedTab: Int,
    modifierEnabled: Boolean,
    onSelectTab: (Int) -> Unit,
    onModifierBlocked: () -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onSelectTab(0) },
            icon = { Icon(Icons.Filled.Refresh, null) },
            label = { Text(stringResource(R.string.create)) }
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onSelectTab(1) },
            icon = { Icon(Icons.Filled.Face, null) },
            label = { Text(stringResource(R.string.upload)) }
        )
        NavigationBarItem(
            selected = selectedTab == 3,
            onClick = { onSelectTab(3) },
            icon = { Icon(Icons.Filled.Create, null) },
            label = { Text(stringResource(R.string.editVector)) }
        )
        val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { if (modifierEnabled) onSelectTab(2) else onModifierBlocked() },
            icon = {
                if (modifierEnabled) {
                    Icon(Icons.Filled.Tune, null)
                } else {
                    Icon(Icons.Filled.Tune, null, tint = disabledTint)
                }
            },
            label = {
                if (modifierEnabled) {
                    Text(stringResource(R.string.modifierTab))
                } else {
                    Text(stringResource(R.string.modifierTab), color = disabledTint)
                }
            }
        )
    }
}

/**
 * Android dynamic colour schemes (foreground over background) for tinting the Material You layer:
 * the three accent hues plus a neutral, and an inverted accent. OEM launchers may use a separate
 * private wallpaper palette which third-party apps cannot read. On Android < 12 it falls back to
 * plain light-on-dark / dark-on-light.
 */
@Composable
private fun rememberMaterialYouSchemes(): List<Pair<Color, Color>> {
    if (!supportDynamicColors()) {
        return listOf(Color.White to Color.Black, Color.Black to Color.White)
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        fun c(id: Int) = Color(context.resources.getColor(id, context.theme))
        listOf(
            c(android.R.color.system_accent1_100) to c(android.R.color.system_accent1_800),
            c(android.R.color.system_accent2_100) to c(android.R.color.system_accent2_800),
            c(android.R.color.system_accent3_100) to c(android.R.color.system_accent3_800),
            c(android.R.color.system_neutral1_100) to c(android.R.color.system_neutral1_800),
            c(android.R.color.system_accent1_800) to c(android.R.color.system_accent1_100)
        )
    }
}

@Composable
fun ConfirmClearDialog(onDismiss: () -> Unit, onIconClear: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.confirmClear),
        text = stringResource(R.string.confirmClearText),
        onConfirm = onIconClear,
        onDismiss = onDismiss
    )
}

/**
 * Shown when the selected icon ends in a number — the user can opt in to day rotation.
 * Works with any icon from any pack regardless of which app it was designed for.
 */
@Composable
private fun CalendarCard(
    packName: String,
    calendarPrefix: String,
    calendarEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = dev.renkinProject.renkin.ui.theme.CardShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.calendarDayIcons),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.calendarDayIconsDesc, calendarPrefix.prettyDrawableName(), packName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
            Switch(
                checked = calendarEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun SourcePills(
    source: Source,
    onSourceChange: (Source) -> Unit
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SegmentedButton(
            selected = source == Source.ICON_PACK,
            onClick = { onSourceChange(Source.ICON_PACK) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
        ) { Text(stringResource(R.string.iconPack)) }
        SegmentedButton(
            selected = source == Source.APPLICATION_ICON,
            onClick = { onSourceChange(Source.APPLICATION_ICON) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
        ) { Text(stringResource(R.string.sourceAppIcon)) }
        SegmentedButton(
            selected = source == Source.APPLICATION_NAME,
            onClick = { onSourceChange(Source.APPLICATION_NAME) },
            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
        ) { Text(stringResource(R.string.sourceText)) }
    }
}
