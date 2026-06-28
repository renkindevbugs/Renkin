@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import dev.alembiconsProject.alembicons.ui.theme.DialogShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.alembiconsProject.alembicons.IconPreviewBuilder
import dev.alembiconsProject.alembicons.MainViewModel
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.extension.calendarPrefixOrNull
import dev.alembiconsProject.alembicons.extension.toInt
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.platform.LocalContext
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.haveMonochrome
import dev.alembiconsProject.alembicons.drawable.isAdaptiveIconDrawable
import dev.alembiconsProject.alembicons.packages.supportDynamicColors
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
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
        generating = true
        createIcon = when {
            // Explicit pick from a pack
            custom != null -> builder.previewIcon(app, options, custom)
            // Icon-pack source with no new pick: apply the modifier to the already saved icon
            // rather than pulling a fresh one from the first pack (which would swap the icon
            // out from under the user). Null until a tap if none.
            options.primarySource == Source.ICON_PACK ->
                app.createdIcon?.let { builder.applyModifier(it, options) }
            // Text / app-icon sources generate from the source itself
            else -> builder.previewIcon(app, options, null)
        }
        generating = false
    }

    /** Reapplies the shared modifier to the hand-edited vector (it isn't built from a source). */
    suspend fun regenerateVector(builder: IconPreviewBuilder, options: GenerationOptions) {
        val base = vectorIcon
        modifiedVector = when {
            base == null -> null
            // Only skip when there's truly nothing to apply — scale (iconScale) is applied by
            // applyModifier too, so a scale change with no image-edit must still run it.
            options.primaryImageEdit == ImageEdit.NONE && options.iconScale == 1f -> base
            else -> {
                generating = true
                val result = builder.applyModifier(base, options)
                generating = false
                result
            }
        }
    }

    /** Reapplies the shared modifier (edit / color / scale) to the uploaded image. */
    suspend fun regenerateUpload(builder: IconPreviewBuilder, options: GenerationOptions) {
        val base = uploadBase
        uploadIcon = if (base == null) null else builder.applyModifier(base, options)
    }
}

@Composable
fun OptionsDialog(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    themed: Boolean,
    onConfirm: (icon: IconPackDrawable?, calendarEnabled: Boolean, calendarPrefix: String?, calendarPackName: String?, sourcePackName: String?) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf(Source.ICON_PACK) }
    var imageEdit by rememberSaveable { mutableStateOf(ImageEdit.NONE) }
    var textType by rememberSaveable { mutableStateOf(TextType.FULL_NAME) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    // Monochrome variant: which colour scheme tints the icon. 0..schemes-1 pick a wallpaper-derived
    // Material You scheme (foreground+background); the last index is Custom (manual colour below).
    var monochromeScheme by rememberSaveable { mutableIntStateOf(0) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    // Background for the monochrome variant's Custom scheme (the system schemes carry their own).
    var customBgColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.Black) }
    var iconPack by rememberSaveable { mutableStateOf(iconPacks.firstOrNull()?.packageName ?: "") }
    // remember (not rememberSaveable): ResourceDrawable holds a live Drawable that isn't
    // Parcelable, so saving the list on stop crashes.
    var customIconList by remember { mutableStateOf<List<ResourceDrawable>>(listOf()) }
    // The Create tab's icon search. Hoisted here (not inside CreateTab) so it survives leaving
    // and returning to the tab; it starts at the app's non-localized name and resets per dialog
    // (i.e. per edit) — icon packs name drawables in English, so the localized label rarely matches.
    var createSearchQuery by rememberSaveable { mutableStateOf(app.originalName) }
    // How often each pack has been used so far, so the icon-pack list can put the user's
    // most-used packs near the top. Loaded once when the dialog opens.
    var packUsage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) { packUsage = viewModel.packUsageCounts() }
    // The draft icon being built (create/upload/vector previews) and the generation logic
    // that produces it. See IconDraftState — keeps the dozen drawable states + the regen
    // effects out of this composable.
    val draft = remember { IconDraftState(app.createdIcon) }
    // Hoisted above the tab AnimatedContent so leaving the vector tab and coming back
    // keeps the user's paths instead of disposing the editor and resetting them.
    val vectorEditState = remember { VectorEditState() }
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
    var edgeThreshold by rememberSaveable { mutableFloatStateOf(2.5f) }
    var edgeSmoothing by rememberSaveable { mutableFloatStateOf(2f) }
    var edgeContrast by rememberSaveable { mutableStateOf(false) }
    var iconScale by rememberSaveable { mutableFloatStateOf(1f) }
    var bgRemovalTolerance by rememberSaveable { mutableFloatStateOf(0.1f) }

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

    // Whether the app ships a Material You <monochrome> layer, enabling the Monochrome variant.
    val appHasMonochrome = remember(app.icon) {
        val icon = app.icon
        icon.isAdaptiveIconDrawable() && (icon as AdaptiveIconDrawable).haveMonochrome()
    }

    // Monochrome variant colours: a wallpaper-derived scheme (foreground+background) or Custom.
    val isMonochromeVariant = source == Source.APPLICATION_ICON && useMonochrome
    val monochromeSchemes = rememberMonochromeSchemes()
    val isCustomScheme = monochromeScheme >= monochromeSchemes.size
    val scheme = monochromeSchemes.getOrNull(monochromeScheme)
    val effectiveColor = if (isMonochromeVariant && !isCustomScheme) scheme!!.first else iconColor
    // Background only applies to the monochrome variant; other sources keep the transparent default.
    val effectiveBgColor = when {
        isMonochromeVariant && !isCustomScheme -> scheme!!.second
        isMonochromeVariant -> customBgColor
        else -> Color.Transparent
    }

    val generatingOptions = GenerationOptions(
        source, imageEdit, textType, iconPack,
        effectiveColor.toInt(), effectiveBgColor.toInt(), useVector, useMonochrome, themed, override = true,
        edgeLowThreshold = edgeThreshold,
        edgeHighThreshold = edgeThreshold * 3f,
        edgeGaussianRadius = edgeSmoothing,
        edgeContrastNormalized = edgeContrast,
        iconScale = iconScale,
        bgRemovalTolerance = bgRemovalTolerance
    )

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

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val selectIconMessage = stringResource(R.string.selectIconFirst)
    val context = LocalContext.current

    LaunchedEffect(selectedTab) {
        // Leaving the icon-pack list re-expands the app bar (other tabs barely scroll).
        if (selectedTab != 0) headerScrollBehavior.state.heightOffset = 0f
        // Modifiers live only in the Modifier tab — leaving it starts the next visit clean
        if (selectedTab != 2) {
            imageEdit = ImageEdit.NONE
        }
    }

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
                    .nestedScroll(headerScrollBehavior.nestedScrollConnection)
            ) {
            Column(Modifier.fillMaxSize()) {
                // Sticky comparison header — close/delete/apply live in the same row
                // and the icons shrink while the icon list is scrolled
                ComparisonHeader(
                    heroBitmap = heroBitmap,
                    appName = app.appName,
                    previewIcon = draft.iconToConfirm,
                    previewLoading = draft.generating,
                    onDismiss = startClose,
                    onClear = { showConfirmClear = true },
                    onConfirm = {
                        // Credit the icon to a pack only when it actually came from one: the Create
                        // tab's Icon Pack source. A fresh pick uses the picked pack; keeping the
                        // existing icon keeps its stored source. Upload/vector/text/app-icon = none.
                        val confirmedSourcePack = when {
                            draft.origin == IconOrigin.CREATE && source == Source.ICON_PACK ->
                                if (customIconList.isNotEmpty()) iconPack else app.sourcePackName ?: ""
                            else -> ""
                        }
                        onConfirm(draft.iconToConfirm, calendarEnabled, calendarPrefix, calendarPackName, confirmedSourcePack)
                    },
                    onEditExternally = {
                        val bitmap = draft.iconToConfirm?.toBitmap()
                        if (bitmap != null) shareIconForEditing(context, bitmap)
                        else snackbarScope.launch { snackbarHostState.showSnackbar(selectIconMessage) }
                    },
                    scrollBehavior = headerScrollBehavior,
                    labelExpand = labelExpand
                )

                // The Create tab draws its own divider under the search bar;
                // the other tabs get one right below the header
                if (selectedTab != 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        onToggle = { enabled ->
                            calendarEnabled = enabled
                            viewModel.setCalendarEnabled(app, enabled, calendarPrefix, calendarPackName)
                        }
                    )
                }

                // Tab content
                Box(modifier = Modifier.weight(1f)) {
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
                                iconPacks = iconPacks,
                                options = generatingOptions,
                                textType = textType,
                                listState = iconListState,
                                gridState = iconGridState,
                                expandedPack = expandedPack,
                                onExpandedPackChange = { expandedPack = it },
                                // Same enter-always scroll behaviour as the app bar collapses the
                                // pinned search bar pixel-by-pixel (read in the layout phase, so the
                                // pack list isn't recomposed each frame).
                                searchBarExpand = { 1f - headerScrollBehavior.state.collapsedFraction },
                                packUsage = packUsage,
                                searchQuery = createSearchQuery,
                                onSearchQueryChange = { createSearchQuery = it },
                                onIconSelect = { res, pack, drawableName ->
                                    customIconList = listOf(res)
                                    iconPack = pack.packageName
                                    draft.origin = IconOrigin.CREATE
                                    // Derive calendar prefix from the picked icon's name.
                                    // Any icon ending in _N is a valid calendar candidate.
                                    val newPrefix = drawableName.calendarPrefixOrNull()
                                    calendarPrefix = newPrefix
                                    calendarPackName = if (newPrefix != null) pack.packageName else null
                                    // If calendar was enabled but the new icon can't rotate, disable it.
                                    if (newPrefix == null && calendarEnabled) {
                                        calendarEnabled = false
                                        viewModel.setCalendarEnabled(app, false, null, null)
                                    }
                                },
                                onTextTypeChange = { textType = it; draft.origin = IconOrigin.CREATE },
                                contentReady = createTabReady,
                                selectedResourceId = customIconList.firstOrNull()?.resourceId,
                                // Frame the rotation siblings only once the user has opted in.
                                selectedCalendarPrefix = calendarPrefix.takeIf { calendarEnabled },
                                appHasMonochrome = appHasMonochrome,
                                onMonochromeChange = { useMonochrome = it },
                                monochromeSchemes = monochromeSchemes,
                                selectedScheme = monochromeScheme,
                                onSchemeChange = { monochromeScheme = it },
                                customForeground = iconColor,
                                customBackground = customBgColor,
                                onCustomForegroundChange = { iconColor = it },
                                onCustomBackgroundChange = { customBgColor = it }
                            )
                            1 -> UploadColumn(app = app) {
                                draft.uploadBase = it
                                if (it != null) draft.origin = IconOrigin.UPLOAD
                            }
                            2 -> ModifierTab(
                                source = source,
                                imageEdit = imageEdit,
                                iconColor = iconColor,
                                useVector = useVector,
                                useMonochrome = useMonochrome,
                                edgeThreshold = edgeThreshold,
                                edgeSmoothing = edgeSmoothing,
                                edgeContrast = edgeContrast,
                                iconScale = iconScale,
                                bgRemovalTolerance = bgRemovalTolerance,
                                onImageEditChange = { imageEdit = it },
                                onColorChange = { iconColor = it },
                                onVectorChange = { useVector = it },
                                onMonochromeChange = { useMonochrome = it },
                                onEdgeThresholdChange = { edgeThreshold = it },
                                onEdgeSmoothingChange = { edgeSmoothing = it },
                                onEdgeContrastChange = { edgeContrast = it },
                                onIconScaleChange = { iconScale = it },
                                onBgRemovalToleranceChange = { bgRemovalTolerance = it }
                            )
                            else -> PrepareEditVector(app, vectorEditState) {
                                draft.vectorIcon = it
                                if (it != null) draft.origin = IconOrigin.VECTOR
                            }
                        }
                    }
                }

                // Source pills — only when Create tab is active
                AnimatedVisibility(visible = selectedTab == 0) {
                    SourcePills(source = source) { newSource ->
                        source = newSource
                        customIconList = listOf()
                        draft.origin = IconOrigin.CREATE
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OptionsBottomBar(
                    selectedTab = selectedTab,
                    modifierEnabled = draft.hasIcon,
                    onSelectTab = { selectedTab = it },
                    onModifierBlocked = {
                        snackbarScope.launch { snackbarHostState.showSnackbar(selectIconMessage) }
                    }
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
 * Wallpaper-derived colour schemes (foreground over background) for tinting the monochrome icon,
 * pulled from the live Material You palette — the three accent hues plus a neutral, and an inverted
 * accent. These harmonise with the user's wallpaper, like Android's own themed-icon colours. On
 * Android < 12 (no dynamic colours) it falls back to plain light-on-dark / dark-on-light.
 */
@Composable
private fun rememberMonochromeSchemes(): List<Pair<Color, Color>> {
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
        shape = dev.alembiconsProject.alembicons.ui.theme.CardShape,
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
                    text = stringResource(R.string.calendarDayIconsDesc, calendarPrefix, packName),
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


