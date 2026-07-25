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
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.haveMonochrome
import dev.renkinProject.renkin.drawable.isAdaptiveIconDrawable
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight

/** The source that produced the icon currently being previewed and confirmed. */
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
    private var uploadIcon by mutableStateOf<IconPackDrawable?>(null)

    // Hand-edited vector and the same vector with the shared modifier applied.
    var vectorIcon by mutableStateOf<IconPackDrawable?>(null)
    private var modifiedVector by mutableStateOf<IconPackDrawable?>(null)

    /** Which source produced the icon currently being previewed/confirmed. */
    var origin by mutableStateOf(IconOrigin.CREATE)

    /** True while an icon is being (re)generated; drives the spinner over the preview slot. */
    var generating by mutableStateOf(false)
        private set
    private var activeGenerations = 0

    // Keep the existing icon on the first pass — only regenerate once the user actually
    // changes a source, modifier or selects an icon.
    private var initialized = false

    /** The modifier needs something to act on — false greys out the Modifier tab. */
    val hasIcon: Boolean get() = createIcon != null || uploadBase != null || vectorIcon != null

    /**
     * The icon Confirm would store. It follows whichever source produced it, not the open
     * tab, so visiting the Modifier tab never silently drops an upload/vector.
     */
    val iconToConfirm: IconPackDrawable? get() = when (origin) {
        IconOrigin.UPLOAD -> uploadIcon ?: createIcon
        IconOrigin.VECTOR -> modifiedVector
        IconOrigin.CREATE -> createIcon
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

    /** Rebuilds the Create-tab icon for [options] (and an optional explicit pack pick). */
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
        createIcon = trackGeneration {
            when {
                // Explicit pick from a pack
                custom != null -> builder.previewIcon(app, options, custom)
                // Icon-pack source with no new pick: apply the modifier to the already saved icon
                // rather than pulling a fresh one from the first pack (which would swap the icon
                // out from under the user). Null until a tap if none.
                options.primarySource == Source.ICON_PACK ->
                    (app.baseIcon ?: app.createdIcon)?.let { builder.applyModifier(it, options) }
                // Text / app-icon sources generate from the source itself
                else -> builder.previewIcon(app, options, null)
            }
        }
    }

    /** Reapplies the shared modifier to the hand-edited vector (it isn't built from a source). */
    suspend fun regenerateVector(builder: IconPreviewBuilder, options: GenerationOptions) {
        val base = vectorIcon
        modifiedVector = when {
            base == null -> null
            // Only skip when there's truly nothing to apply — scale, shape and outline are
            // applied by applyModifier too, so those changes with no image-edit must run it.
            options.primaryImageEdit == ImageEdit.NONE && options.iconScale == 1f
                && options.iconOffsetX == 0f && options.iconOffsetY == 0f
                && options.iconShape == IconShape.NONE
                && options.outlineMode == OutlineMode.NONE -> base
            else -> trackGeneration { builder.applyModifier(base, options) }
        }
    }

    /** Reapplies the shared modifier (edit / color / scale) to the uploaded image. */
    suspend fun regenerateUpload(builder: IconPreviewBuilder, options: GenerationOptions) {
        val base = uploadBase
        uploadIcon = if (base == null) null else trackGeneration { builder.applyModifier(base, options) }
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
    // Text-source extras: the CUSTOM type's string (seeded with the app name), the letter-case
    // transform, and the font — per-app; the font starts from the global preference.
    var customText by rememberSaveable { mutableStateOf(app.appName) }
    var textCase by rememberSaveable { mutableStateOf(TextCase.AS_IS) }
    val globalFontPath = getPreferences().getStringValue(TextFontKey)
    var textFontPath by rememberSaveable(globalFontPath) { mutableStateOf(globalFontPath) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var applicationIconVariant by rememberSaveable { mutableStateOf(ApplicationIconVariant.DEFAULT) }
    var invertMonochrome by rememberSaveable { mutableStateOf(false) }
    // Material You variant: which colour scheme tints the icon. 0..schemes-1 pick a wallpaper-derived
    // Material You scheme (foreground+background); the last index is Custom (manual colour below).
    var materialYouScheme by rememberSaveable { mutableIntStateOf(0) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    // Background for the Material You variant's Custom scheme (the system schemes carry their own).
    var customBgColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.Black) }
    var iconPack by rememberSaveable { mutableStateOf(iconPacks.firstOrNull()?.packageName ?: "") }
    // remember (not rememberSaveable): ResourceDrawable holds a live Drawable that isn't
    // Parcelable, so saving the list on stop crashes.
    var customIconList by remember { mutableStateOf<List<ResourceDrawable>>(listOf()) }
    // The Create tab's icon search. Hoisted here (not inside CreateTab) so it survives leaving
    // and returning to the tab; it starts at the app's non-localized name and resets per dialog
    // (i.e. per edit) — icon packs name drawables in English, so the localized label rarely matches.
    var createSearchQuery by rememberSaveable { mutableStateOf(app.originalName) }
    // Sorts live in the top bar's menu (Mihon-style bar), so they're dialog state like the query.
    var iconSortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var packSortOrder by rememberSaveable { mutableStateOf(PackSortOrder.USAGE) }
    // True while the pack rows are still loading/resolving the query — the bar's activity line.
    var createBusy by remember { mutableStateOf(false) }
    // How often each pack has been used so far, so the icon-pack list can put the user's
    // most-used packs near the top. Loaded once when the dialog opens.
    var packUsage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) { packUsage = viewModel.packUsageCounts() }
    // The draft icon being built (create/upload/vector previews) and the generation logic
    // that produces it. See IconDraftState — keeps the dozen drawable states + the regen
    // effects out of this composable.
    val draft = remember { IconDraftState(app.baseIcon ?: app.createdIcon) }
    // Hoisted above the tab AnimatedContent so leaving the vector tab and coming back
    // keeps the user's paths instead of disposing the editor and resetting them.
    val vectorEditState = remember { VectorEditState() }
    // Attribution URL for an online icon imported "as image" (it lands in the upload draft,
    // not the vector editor) and the gallery file it was saved as. The attribution is
    // dropped as soon as the gallery selects any other picture.
    var onlineImageUrl by remember { mutableStateOf<String?>(null) }
    var onlineImagePath by remember { mutableStateOf<String?>(null) }
    var showConfirmClear by remember { mutableStateOf(false) }
    // Enter-always app bar: the header (close / name / overflow) collapses pixel-by-pixel as the
    // icon list scrolls down and slides back in on scroll up.
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
    // The Modifier tab's adjustment values (edge tuning, scale, tolerance, position), bundled in
    // one saveable holder instead of eight loose rememberSaveables + callback pairs.
    // The global "Add outline" preference is deliberately NOT seeded here: it applies to the
    // bulk refresh's own (hero-source) icons only, never to hand-picked ones. Per-app outline
    // stays an explicit choice in the Modifier tab.
    val adjustments = rememberSaveable(saver = AdjustmentState.Saver) { AdjustmentState() }
    val materialYouPackAdjustments = rememberSaveable(
        saver = MaterialYouPackAdjustmentState.Saver
    ) { MaterialYouPackAdjustmentState() }

    // Calendar day icons — committed immediately when toggled (independent of icon confirm).
    var calendarEnabled by rememberSaveable { mutableStateOf(app.calendarEnabled) }
    // Prefix derived from the selected icon's drawable name (e.g. "bee_calendar_").
    // Persisted in PackageInfoStruct so it survives dialog reopen. Non-null only when
    // the currently selected icon ends in _N (meaning day rotation is possible).
    var calendarPrefix by remember { mutableStateOf(app.calendarPrefix) }
    // Package name of the pack the calendar drawables come from (stored per-app in DB).
    var calendarPackName by remember { mutableStateOf(app.calendarPackName) }
    val calendarPackLabel = iconPacks.find { it.packageName == (calendarPackName ?: iconPack) }?.applicationName ?: ""

    // Slide the editor in from the right; closing plays the reverse animation
    // before the dialog window is actually dismissed
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

    // The comparison header's "current" hero. The shared helper handles the BitmapDrawable
    // fast path and safely rasterises everything else (a broken icon yields null → placeholder).
    val heroBitmap = remember(app.icon) {
        runCatching { app.icon.toSafeBitmapOrNull() }.getOrNull()
    }

    // Whether the app ships an official Material You <monochrome> layer. Apps without one use
    // Renkin's labelled generated fallback instead.
    val appHasMaterialYouIcon = remember(app.icon) {
        val icon = app.icon
        icon.isAdaptiveIconDrawable() && (icon as AdaptiveIconDrawable).haveMonochrome()
    }

    // Material You colours: a wallpaper-derived scheme (foreground+background) or Custom.
    val isMaterialYouVariant = source == Source.APPLICATION_ICON &&
        applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU
    val materialYouSchemes = rememberMaterialYouSchemes()
    val selectedMaterialYouPackIcon = source == Source.ICON_PACK &&
        customIconList.firstOrNull()?.drawable?.isAdaptiveIconDrawable() == true &&
        iconPacks.firstOrNull { it.packageName == iconPack }
            ?.changesWithMaterialYouColors == true
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
        adjustments.iconShape != IconShape.NONE && !adjustments.shapeCrop -> adjustments.shapeColor
        else -> Color.Transparent
    }
    val selectedPackScheme = materialYouSchemes.getOrNull(
        materialYouPackAdjustments.selectedScheme
    )
    val materialYouPackForeground = when {
        !selectedMaterialYouPackIcon || materialYouPackAdjustments.selectedScheme < 0 -> null
        materialYouPackAdjustments.selectedScheme >= materialYouSchemes.size ->
            materialYouPackAdjustments.customForeground.toInt()
        else -> selectedPackScheme!!.first.toInt()
    }
    val materialYouPackBackground = when {
        !selectedMaterialYouPackIcon || materialYouPackAdjustments.selectedScheme < 0 -> null
        materialYouPackAdjustments.selectedScheme >= materialYouSchemes.size ->
            materialYouPackAdjustments.customBackground.toInt()
        else -> selectedPackScheme!!.second.toInt()
    }

    val generatingOptions = GenerationOptions(
        source, imageEdit, textType, iconPack,
        effectiveColor.toInt(), effectiveBgColor.toInt(), useVector,
        materialYou = applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU,
        themed = themed,
        override = true,
        edgeLowThreshold = adjustments.edgeThreshold,
        edgeHighThreshold = adjustments.edgeThreshold * 3f,
        edgeGaussianRadius = adjustments.edgeSmoothing,
        edgeContrastNormalized = adjustments.edgeContrast,
        iconScale = adjustments.iconScale,
        bgRemovalTolerance = adjustments.bgRemovalTolerance,
        iconOffsetX = adjustments.iconOffsetX,
        iconOffsetY = adjustments.iconOffsetY,
        colorizeFlat = adjustments.colorizeFlat,
        colorizeMonochrome = adjustments.colorizeMonochrome,
        colorizeInverse = adjustments.colorizeInverse,
        colorizerMode = adjustments.colorizerMode,
        colorizerGradientType = adjustments.colorizerGradientType,
        colorizerGradientColors = adjustments.colorizerGradientColors,
        colorizerGradientAngle = adjustments.colorizerGradientAngle,
        // Layers only apply to the segment modifier; plain Colorize always paints it all.
        colorizeLayers = if (imageEdit == ImageEdit.COLORIZE_SEGMENTS) {
            adjustments.colorizeLayers
        } else emptyList(),
        iconShape = adjustments.iconShape,
        iconShapeCrop = adjustments.shapeCrop,
        iconShapeScale = adjustments.shapeScale,
        outlineMode = adjustments.outlineMode,
        outlineWidth = adjustments.outlineWidth,
        outlineColor = adjustments.outlineColor.toInt(),
        outlineStyle = ColorizerStyle(
            mode = adjustments.outlineColorizerMode,
            gradientType = adjustments.outlineGradientType,
            firstColor = adjustments.outlineColor.toInt(),
            gradientStops = adjustments.outlineGradientColors,
            gradientAngle = adjustments.outlineGradientAngle
        ),
        // Memoised per stroke list: the options object must only change when the strokes do,
        // or every recomposition would look like a new mask and re-trigger generation.
        outlineEraseMask = remember(adjustments.eraseStrokes) {
            if (adjustments.eraseStrokes.isEmpty()) null else buildEraseMask(adjustments.eraseStrokes)
        },
        textCustom = customText,
        textCase = textCase,
        textFontPath = textFontPath,
        applicationIconVariant = applicationIconVariant,
        invertMonochrome = invertMonochrome,
        materialYouPackForeground = materialYouPackForeground,
        materialYouPackBackground = materialYouPackBackground,
        materialYouPackStrokeScale = if (selectedMaterialYouPackIcon) {
            materialYouPackAdjustments.strokeScale
        } else 1f
    )
    // Pack rows describe the source artwork, not the per-icon draft currently being edited.
    // Keeping these options separate prevents a Material You slider from restyling every
    // Lawnicons tile while the selected icon alone is regenerated below.
    val browserOptions = generatingOptions.copy(
        materialYouPackForeground = null,
        materialYouPackBackground = null,
        materialYouPackStrokeScale = 1f
    )

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
                    previewOptions.primarySource == Source.ICON_PACK ->
                        (app.baseIcon ?: app.createdIcon)?.let {
                            viewModel.applyModifier(it, previewOptions)
                        }
                    else -> viewModel.previewIcon(app, previewOptions, null)
                }
            }
        }
        rendered?.toBitmap()
    }
    val renderColorizePreview: suspend (ColorizerStyle) -> Bitmap? = { style ->
        renderPreviewWith(
            generatingOptions.copy(
                primaryImageEdit = if (style.segmentTargets.isEmpty()) {
                    ImageEdit.COLORIZE
                } else ImageEdit.COLORIZE_SEGMENTS,
                color = style.firstColor,
                colorizeFlat = style.flat,
                colorizeMonochrome = style.monochrome,
                colorizeInverse = style.inverse,
                colorizerMode = style.mode,
                colorizerGradientType = style.gradientType,
                colorizerGradientColors = style.gradientStops,
                colorizerGradientAngle = style.gradientAngle,
                colorizeLayers = emptyList()
            )
        )
    }
    // Previews the whole layer stack of the segment modifier, with [draft] standing in for the
    // layer being edited so the sheet shows the layer in the context of the others.
    val renderLayersPreview: suspend (Int, ColorizerStyle) -> Bitmap? = { index, draft ->
        renderPreviewWith(
            generatingOptions.copy(
                primaryImageEdit = ImageEdit.COLORIZE_SEGMENTS,
                colorizeLayers = adjustments.colorizeLayers.mapIndexed { i, layer ->
                    if (i == index) layer.copy(style = draft) else layer
                }
            )
        )
    }
    // The icon with every modifier EXCEPT colourize, so segment colours match what the
    // generator will actually see when it colourizes.
    var colorizeBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(generatingOptions, customIconList) {
        colorizeBaseBitmap = renderPreviewWith(
            generatingOptions.copy(primaryImageEdit = ImageEdit.NONE)
        )
    }
    val renderOutlinePreview: suspend (ColorizerStyle) -> Bitmap? = { style ->
        renderPreviewWith(
            generatingOptions.copy(
                // The outline is only visible once it is actually being drawn.
                outlineMode = adjustments.outlineMode.takeIf { it != OutlineMode.NONE }
                    ?: OutlineMode.ADD,
                outlineColor = style.firstColor,
                outlineStyle = style
            )
        )
    }

    // Regenerate the preview when the options (or the explicit pick) change. The heavy work
    // hops to Dispatchers.Default inside the view model; the holder drives the spinner.
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

    // Shared by both layouts' Apply buttons (phone header card, wide preview pane).
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

    // Tablets / unfolded foldables (and landscape phones): two panes — persistent preview
    // pane left, tabs right — instead of the collapsing phone header. Same threshold as the
    // wide ComparisonHeader and Global options.
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 600

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
                        // The collapsing header exists only in the phone layout; the wide
                        // layout's chrome is static, so no scroll connection to feed.
                        if (wideLayout) Modifier
                        else Modifier.nestedScroll(headerScrollBehavior.nestedScrollConnection)
                    )
            ) {
                // The Create tab's icon-pack browser gets the Mihon-style search bar chrome.
                val packBrowsing = selectedTab == 0 && source == Source.ICON_PACK
                // The tab contents, shared by the phone and wide layouts (all state is hoisted
                // above, so folding/unfolding mid-edit keeps everything).
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
                                    draft.origin = IconOrigin.CREATE
                                    // Derive calendar prefix from the picked icon's name.
                                    // Any icon ending in _N is a valid calendar candidate.
                                    val newPrefix = drawableName.calendarPrefixOrNull()
                                    calendarPrefix = newPrefix
                                    calendarPackName = if (newPrefix != null) pack.packageName else null
                                    // If calendar was enabled but the new icon can't rotate, disable it
                                    // (locally — like the toggle, this commits on Apply).
                                    if (newPrefix == null && calendarEnabled) {
                                        calendarEnabled = false
                                    }
                                },
                                onTextTypeChange = { textType = it; draft.origin = IconOrigin.CREATE },
                                customText = customText,
                                onCustomTextChange = { customText = it; draft.origin = IconOrigin.CREATE },
                                textCase = textCase,
                                onTextCaseChange = { textCase = it; draft.origin = IconOrigin.CREATE },
                                fontPath = textFontPath,
                                onFontPathChange = { textFontPath = it; draft.origin = IconOrigin.CREATE },
                                contentReady = createTabReady,
                                selectedResourceId = customIconList.firstOrNull()?.resourceId,
                                // Frame the rotation siblings only once the user has opted in.
                                selectedCalendarPrefix = calendarPrefix.takeIf { calendarEnabled },
                                appHasMaterialYouIcon = appHasMaterialYouIcon,
                                applicationIconVariant = applicationIconVariant,
                                onApplicationIconVariantChange = {
                                    applicationIconVariant = it
                                    draft.origin = IconOrigin.CREATE
                                },
                                invertMonochrome = invertMonochrome,
                                onInvertMonochromeChange = {
                                    invertMonochrome = it
                                    draft.origin = IconOrigin.CREATE
                                },
                                materialYouSchemes = materialYouSchemes,
                                selectedScheme = materialYouScheme,
                                onSchemeChange = {
                                    materialYouScheme = it
                                    draft.origin = IconOrigin.CREATE
                                },
                                customForeground = iconColor,
                                customBackground = customBgColor,
                                onCustomForegroundChange = {
                                    iconColor = it
                                    draft.origin = IconOrigin.CREATE
                                },
                                onCustomBackgroundChange = {
                                    customBgColor = it
                                    draft.origin = IconOrigin.CREATE
                                }
                            )
                            // The static tabs don't scroll under the header — plain top padding.
                            1 -> Box(Modifier.fillMaxSize().padding(headerPadding)) {
                                UploadColumn(
                                    app = app,
                                    snackbarHostState = snackbarHostState,
                                    initialSelectedPath = onlineImagePath
                                ) { icon, path ->
                                    draft.uploadBase = icon
                                    // A manual gallery pick replaces an online "as image"
                                    // import, so its attribution must not outlive the
                                    // picture; re-selecting the online file keeps it.
                                    if (path != onlineImagePath) onlineImageUrl = null
                                    if (icon != null) draft.origin = IconOrigin.UPLOAD
                                }
                            }
                            2 -> Box(Modifier.fillMaxSize().padding(headerPadding)) { ModifierTab(
                                source = source,
                                imageEdit = imageEdit,
                                iconColor = iconColor,
                                useVector = useVector,
                                useMaterialYou = applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU,
                                adjustments = adjustments,
                                centerPreview = remember(draft.iconToConfirm) { draft.iconToConfirm?.toBitmap() },
                                previewGenerating = draft.generating,
                                sampleBitmap = heroBitmap,
                                renderColorizePreview = renderColorizePreview,
                                renderOutlinePreview = renderOutlinePreview,
                                renderLayersPreview = renderLayersPreview,
                                colorizeBaseBitmap = colorizeBaseBitmap,
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
                                            val bitmap = withContext(Dispatchers.Default) { icon.toBitmap() }
                                            val opened = if (toolbox) {
                                                openInImageToolbox(context, bitmap)
                                            } else {
                                                editInAnotherApp(context, bitmap)
                                            }
                                            if (!opened) toaster.show(externalEditorError)
                                        }
                                    }
                                }
                            ) }
                            else -> Box(Modifier.fillMaxSize().padding(headerPadding)) {
                                PrepareEditVector(
                                    app = app,
                                    state = vectorEditState,
                                    onImportedImage = { imported, url ->
                                        // "Use as image" from the online browser: route the
                                        // full-size raster through the upload pipeline so the
                                        // shared modifier applies like any uploaded picture;
                                        // the gallery copy arrives preselected in Upload.
                                        draft.uploadBase = BitmapIconDrawable(imported.bitmap, false)
                                        draft.origin = IconOrigin.UPLOAD
                                        onlineImageUrl = url
                                        onlineImagePath = imported.galleryPath
                                    }
                                ) {
                                    draft.vectorIcon = it
                                    if (it != null) draft.origin = IconOrigin.VECTOR
                                }
                            }
                        }
                    }
                }

                // Source pills + bottom tab bar, identical on both layouts.
                val bottomSection: @Composable () -> Unit = {
                    AnimatedVisibility(visible = selectedTab == 0) {
                        SourcePills(source = source) { newSource ->
                            source = newSource
                            customIconList = listOf()
                            draft.origin = IconOrigin.CREATE
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

                if (wideLayout) {
                    // Tablets / unfolded foldables: persistent preview pane (big live New
                    // preview + always-visible Apply) left, the tabs at full height right.
                    Row(Modifier.fillMaxSize()) {
                        EditPreviewPane(
                            heroBitmap = heroBitmap,
                            appName = app.appName,
                            previewIcon = draft.iconToConfirm,
                            previewLoading = draft.generating,
                            confirmEnabled = !draft.generating,
                            onDismiss = startClose,
                            onClear = { showConfirmClear = true },
                            onConfirm = confirmIcon,
                            modifier = Modifier.width(320.dp),
                            extraCard = if (selectedTab == 0 && source == Source.ICON_PACK && calendarPrefix != null) {
                                {
                                    CalendarCard(
                                        packName = calendarPackLabel,
                                        calendarPrefix = calendarPrefix ?: "",
                                        calendarEnabled = calendarEnabled,
                                        onToggle = { enabled -> calendarEnabled = enabled }
                                    )
                                }
                            } else null
                        )
                        VerticalDivider()
                        Column(Modifier.weight(1f)) {
                            // The pack browser's chrome moves atop the right pane: back arrow
                            // (inside a pack), inline search and the sort menu.
                            if (packBrowsing) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (expandedPack != null) {
                                        IconButton(onClick = { expandedPack = null }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(R.string.dismiss)
                                            )
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        AppBarSearchField(
                                            query = createSearchQuery,
                                            onQueryChange = { createSearchQuery = it },
                                            placeholder = stringResource(R.string.searchIcons)
                                        )
                                    }
                                    IconSortMenuButton(
                                        sortOrder = iconSortOrder,
                                        onSortOrderChange = { iconSortOrder = it },
                                        packSortOrder = packSortOrder,
                                        onPackSortOrderChange = { packSortOrder = it }
                                    )
                                }
                                if (createBusy) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                    )
                                }
                            }
                            HorizontalDivider()
                            Box(Modifier.weight(1f)) { tabContent(PaddingValues(0.dp)) }
                            bottomSection()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                // Mihon-style scroll-under chrome: the header overlays the tab content instead of
                // stacking above it, so the enter-always bar collapse only moves the header — the
                // content keeps its size and just scrolls beneath, which keeps flings smooth.
                OverlayHeaderLayout(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    header = {
                // Opaque header background: content scrolling underneath must not show through
                // the transparent app bar or around the comparison card.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                // Sticky comparison header — close/delete/apply live in the same row
                // and the icons shrink while the icon list is scrolled
                ComparisonHeader(
                    heroBitmap = heroBitmap,
                    appName = app.appName,
                    previewIcon = draft.iconToConfirm,
                    previewLoading = draft.generating,
                    confirmEnabled = !draft.generating,
                    onDismiss = startClose,
                    onClear = { showConfirmClear = true },
                    onConfirm = confirmIcon,
                    scrollBehavior = headerScrollBehavior,
                    labelExpand = labelExpand,
                    // Mihon-style bar on the icon-pack browser: back arrow + inline search +
                    // sort menu, with a thin activity line while the search still resolves.
                    titleContent = if (packBrowsing) {
                        {
                            AppBarSearchField(
                                query = createSearchQuery,
                                onQueryChange = { createSearchQuery = it },
                                placeholder = stringResource(R.string.searchIcons)
                            )
                        }
                    } else null,
                    extraActions = if (packBrowsing) {
                        {
                            IconSortMenuButton(
                                sortOrder = iconSortOrder,
                                onSortOrderChange = { iconSortOrder = it },
                                packSortOrder = packSortOrder,
                                onPackSortOrderChange = { packSortOrder = it }
                            )
                        }
                    } else null,
                    // Unified chrome: every tab uses the back arrow. Inside a pack it returns
                    // to the pack list; everywhere else it closes the dialog.
                    onNavigateBack = {
                        if (packBrowsing && expandedPack != null) expandedPack = null else startClose()
                    },
                    showProgress = packBrowsing && createBusy
                )

                // The Create tab draws its own divider under the search bar;
                // the other tabs get one right below the header
                if (selectedTab != 0) {
                    HorizontalDivider()
                }

                // Calendar card: visible only on Create tab with Icon Pack source when
                // the selected pack declares a <calendar> entry for this app.
                AnimatedVisibility(
                    visible = selectedTab == 0 && source == Source.ICON_PACK && calendarPrefix != null
                ) {
                    CalendarCard(
                        packName = calendarPackLabel,
                        calendarPrefix = calendarPrefix ?: "",
                        calendarEnabled = calendarEnabled,
                        // Local state only: the calendar choice commits together with the icon
                        // on Apply (the applyIcon overload). Persisting it here leaked the
                        // prefix of a browsed-but-never-confirmed icon into the stored app.
                        onToggle = { enabled -> calendarEnabled = enabled }
                    )
                }
                }
                    }
                ) { headerPadding -> tabContent(headerPadding) }

                bottomSection()
                    }
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


/**
 * The dialog's bottom tab bar. Create / Upload / Edit-vector switch freely; the Modifier tab
 * sits last and stays greyed out until there is an icon to act on ([modifierEnabled]) — tapping
 * it then calls [onModifierBlocked] (a "select an icon first" hint) instead of switching.
 */
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
            // When enabled, let NavigationBarItem apply its own selected/unselected colours
            // (matching the other tabs); only override when disabled
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
 * Wallpaper-derived colour schemes (foreground over background) for tinting the Material You layer,
 * pulled from the live Material You palette — the three accent hues plus a neutral, and an inverted
 * accent. These harmonise with the user's wallpaper, like Android's own themed-icon colours. On
 * Android < 12 (no dynamic colours) it falls back to plain light-on-dark / dark-on-light.
 */
@Composable
private fun rememberMaterialYouSchemes(): List<Pair<Color, Color>> {
    if (!supportDynamicColors()) {
        return listOf(Color.White to Color.Black, Color.Black to Color.White)
    }
    val context = LocalContext.current
    return remember {
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
