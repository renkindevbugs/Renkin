@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui


import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Science
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.GlobalOptionsViewModel
import dev.renkinProject.renkin.GlobalIconApplyState
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TEXT_TYPE_DEFAULT
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import android.graphics.Bitmap
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.isMissingIcon
import dev.renkinProject.renkin.ui.theme.OnWallpaperTextShadow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.IconShape as IconTileShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.util.runCatchingCancellable

private const val GLOBAL_PREVIEW_DEBOUNCE_MS = 120L

/** Null means idle; a null fraction with active progress is the final persistence phase. */
internal fun globalApplyProgressFraction(progress: Pair<Int, Int>?): Float? = progress
    ?.takeIf { (completed, total) -> total > 0 && completed < total }
    ?.let { (completed, total) -> completed / total.toFloat() }

internal data class GlobalIconCategories(
    val generated: List<PackageInfoStruct>,
    val custom: List<PackageInfoStruct>,
    val existing: List<PackageInfoStruct>,
    val iconless: List<PackageInfoStruct>
)

internal fun categorizeGlobalIcons(
    applications: List<PackageInfoStruct>,
    lockedKeys: Set<String>
): GlobalIconCategories {
    val generated = ArrayList<PackageInfoStruct>()
    val custom = ArrayList<PackageInfoStruct>()
    val existing = ArrayList<PackageInfoStruct>()
    val iconless = ArrayList<PackageInfoStruct>()

    for (app in applications) {
        if (app.createdIcon == null) {
            if (app.isMissingIcon(lockedKeys)) iconless += app
        } else if (app.isCustom) {
            custom += app
        } else if (app.isRefreshMade) {
            generated += app
        } else {
            existing += app
        }
    }

    return GlobalIconCategories(generated, custom, existing, iconless)
}

/**
 * Fullscreen Global options, hosted by [dev.renkinProject.renkin.GlobalOptionsActivity] whose
 * windowShowWallpaper theme puts the REAL wallpaper behind the transparent icon grid: the
 * staged generation sources and global modifiers, their target groups, and a before/after
 * preview grid split into generated / custom / existing / iconless apps. Apply re-renders the
 * global layer from immutable icon bases and persists the profile; tapping a tile opens a
 * per-app editor. [onClose] reports the per-icon edits and whether an apply happened, so
 * MainViewModel can update its session bookkeeping.
 */
@Composable
fun GlobalOptionsScreen(onClose: () -> Unit) {
    val viewModel: GlobalOptionsViewModel = hiltViewModel()
    val prefsValue = getPreferences().getPreferencesValue()
    val context = LocalContext.current
    val view = LocalView.current
    val githubIssuesUrl = stringResource(R.string.crashGithubUrl)
    val openLink = rememberLinkOpener()

    val state = viewModel.draft
    var showBetaDialog by rememberSaveable { mutableStateOf(true) }

    val applying = viewModel.globalApplyProgress != null
    val previewJobs by viewModel.previewJobs.collectAsStateWithLifecycle()
    val dirty by remember(viewModel) { derivedStateOf { viewModel.isDirty } }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showBefore by rememberSaveable { mutableStateOf(false) }
    val panel = rememberSaveable(saver = GlobalStylePanelState.Saver) { GlobalStylePanelState() }
    var confirmBake by rememberSaveable { mutableStateOf(false) }
    var undoBakeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var previewDebouncing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (applying) Unit
        else if (panel.back()) Unit
        else if (dirty) confirmDiscard = true
        else onClose()
    }
    val openPanel: (GlobalStylePanel) -> Unit = { destination ->
        panel.open(destination)
        showBefore = false
    }
    androidx.activity.compose.BackHandler(enabled = true) { close() }

    // Staged modifiers → the preview options. null = nothing to apply (tiles show the icon
    // as-is without spinning up a generation per tile).
    val tileOptions by remember(state) {
        derivedStateOf {
            if (state.hasAnyEffect) state.toModifierOptions() else null
        }
    }
    // Empty slots preview a full generation with the staged globals overlaid, so what's shown
    // matches what Save (and a later refresh) actually produces.
    val emptySourceOptions by remember(state, prefsValue, context) {
        derivedStateOf {
            if (state.includeEmpty) {
                GenerationOptions.fromPreferences(state.overlay(prefsValue), context, override = true)
            } else null
        }
    }
    LaunchedEffect(emptySourceOptions, tileOptions) {
        previewDebouncing = true
        viewModel.updatePreviewConfiguration(emptySourceOptions, tileOptions)
        delay(GLOBAL_PREVIEW_DEBOUNCE_MS)
        previewDebouncing = false
    }
    val previewRefreshing = !showBefore &&
        (previewDebouncing || previewJobs > 0 || viewModel.initialLoadRunning)

    val applicationState by viewModel.applicationState.collectAsStateWithLifecycle()
    val categories = remember(applicationState.revision) {
        categorizeGlobalIcons(applicationState.applications, applicationState.lockedIconKeys)
    }
    val generated = categories.generated
    val custom = categories.custom
    val existing = categories.existing
    val iconless = categories.iconless
    val iconPackState by viewModel.iconPackState.collectAsStateWithLifecycle()
    val iconPacks = iconPackState.packs

    val targetCount by remember(state, categories) {
        derivedStateOf {
            (if (state.applyGenerated) generated.size else 0) +
                (if (state.applyCustom) custom.size else 0) +
                (if (state.applyExisting) existing.size else 0) +
                (if (state.includeEmpty) iconless.size else 0)
        }
    }
    val activeEffectCount by remember(state) {
        derivedStateOf {
            listOf(
                state.shape.isEnabled,
                !state.modifiers.transform.isIdentity,
                state.outline.isEnabled,
                state.shadow.enabled,
                state.modifiers.effect.imageEdit == ImageEdit.COLORIZE
            ).count { it }
        }
    }

    val iconEditor = viewModel.iconEditor

    if (showBetaDialog) {
        GlobalBetaDialog(
            onOpenIssues = {
                showBetaDialog = false
                openLink(githubIssuesUrl)
            },
            onDismiss = { showBetaDialog = false }
        )
    }

    LaunchedEffect(panel.page) {
        if (panel.page == GlobalStylePanel.HISTORY) viewModel.refreshHistory()
    }
    val historyContent: @Composable () -> Unit = {
        GlobalStyleHistoryContent(
            history = viewModel.history,
            current = viewModel.baseline,
            previewApps = remember(categories) { categories.generated + categories.existing + categories.custom },
            viewModel = viewModel,
            onLoad = { item ->
                viewModel.loadHistory(item)
                panel.open(GlobalStylePanel.OPTIONS)
            },
            onLoadNoStyle = {
                viewModel.loadNoGlobalStyle()
                panel.open(GlobalStylePanel.OPTIONS)
            },
            onDelete = viewModel::deleteHistory,
            canBake = !dirty && !applying,
            onBake = {
                panel.open(GlobalStylePanel.OPTIONS)
                confirmBake = true
            },
            onUndoBake = { item ->
                panel.open(GlobalStylePanel.OPTIONS)
                undoBakeId = item.id
            }
        )
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Tiles are the blur sources; the pinned section headers frost whatever scrolls beneath them.
    val hazeState = remember { HazeState() }

    // The preview remains transparent so the real wallpaper is part of the decision. Every
    // control surface paints its own background and therefore stays legible over any wallpaper.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = close, enabled = !applying) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = stringResource(R.string.globalOptionsTitle),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.weight(1f)
                    )
                    GlobalHistoryButton(enabled = !applying && state.initialized) { openPanel(GlobalStylePanel.HISTORY) }
                    Button(
                        onClick = {
                            view.performConfirmHaptic()
                            viewModel.saveGlobalStyle()
                        },
                        enabled = dirty && !applying,
                        shape = dev.renkinProject.renkin.ui.theme.FieldShape
                    ) {
                        Text(
                            if (applying) stringResource(viewModel.globalOperationLabel)
                            else if (targetCount == 0) stringResource(R.string.globalSaveStyle)
                            else stringResource(R.string.globalApplyCount, targetCount)
                        )
                    }
                }
                val progress = viewModel.globalApplyProgress
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    val progressFraction = globalApplyProgressFraction(progress)
                    when {
                        progress == null -> HorizontalDivider(Modifier.align(Alignment.Center))
                        progressFraction == null -> WavyLoadingBar(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                        )
                        else -> WavyProgressBar(
                            progress = progressFraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                        )
                    }
                }
            }

            val gridContent: @Composable () -> Unit = {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(84.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (generated.isEmpty() && custom.isEmpty() && existing.isEmpty() && iconless.isEmpty()) {
                        item(key = "noIcons", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                icon = Icons.Filled.ImageSearch,
                                text = stringResource(R.string.globalNoIcons),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp)
                            )
                        }
                    }

                    if (generated.isNotEmpty()) {
                        sectionHeader(
                            key = "genHeader", hazeState = hazeState,
                            title = { stringResource(R.string.globalGeneratedSection, generated.size) },
                            applies = state.applyGenerated,
                            hint = { stringResource(R.string.globalGridHint) }
                        )
                        items(generated, key = { "g/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (!showBefore && state.applyGenerated) tileOptions else null,
                                viewModel,
                                modifier = Modifier.animateItem().hazeSource(hazeState)
                            ) { viewModel.openIconEditor(app) }
                        }
                    }

                    if (custom.isNotEmpty()) {
                        sectionHeader(
                            key = "customHeader", hazeState = hazeState,
                            title = { stringResource(R.string.globalCustomSection, custom.size) },
                            applies = state.applyCustom
                        )
                        items(custom, key = { "c/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (!showBefore && state.applyCustom) tileOptions else null,
                                viewModel,
                                showEditBadge = true,
                                modifier = Modifier.animateItem().hazeSource(hazeState)
                            ) { viewModel.openIconEditor(app) }
                        }
                    }

                    if (existing.isNotEmpty()) {
                        sectionHeader(
                            key = "existingHeader", hazeState = hazeState,
                            title = { stringResource(R.string.globalExistingSection, existing.size) },
                            applies = state.applyExisting,
                            hint = { stringResource(R.string.globalExistingHint) }
                        )
                        items(existing, key = { "s/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (!showBefore && state.applyExisting) tileOptions else null,
                                viewModel,
                                modifier = Modifier.animateItem().hazeSource(hazeState)
                            ) { viewModel.openIconEditor(app) }
                        }
                    }

                    if (iconless.isNotEmpty()) {
                        sectionHeader(
                            key = "emptyHeader", hazeState = hazeState,
                            title = { stringResource(R.string.globalEmptySection, iconless.size) },
                            applies = state.includeEmpty
                        )
                        items(iconless, key = { "e/${it.key}" }) { app ->
                            GeneratedPreviewTile(
                                app,
                                if (showBefore) null else emptySourceOptions,
                                if (showBefore) null else tileOptions,
                                viewModel,
                                modifier = Modifier.animateItem().hazeSource(hazeState)
                            ) { viewModel.openIconEditor(app) }
                        }
                    }
                }
            }

            val adaptiveLayoutInfo = LocalAdaptiveLayoutInfo.current
            val paneLayout = horizontalPaneLayout(
                availableWidth = adaptiveLayoutInfo.windowWidth,
                preferredLeadingWidth = 360.dp,
                minimumLeadingWidth = 320.dp,
                minimumTrailingWidth = 360.dp,
                separatingVerticalHinge = adaptiveLayoutInfo.separatingVerticalHinge
            )
            if (paneLayout != null) {
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .width(paneLayout.leadingWidth)
                            .fillMaxHeight()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            GlobalStyleControlPanel(
                                state = state,
                                categories = categories,
                                targetCount = targetCount,
                                activeEffectCount = activeEffectCount,
                                iconPacks = iconPacks,
                                resetEnabled = dirty,
                                onReset = viewModel::resetDraft,
                                navigation = panel,
                                onNavigate = openPanel,
                                loadPackIcons = viewModel::iconPackDropdownIcons,
                                historyContent = historyContent,
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                leadingContent = {
                                    viewModel.historyLoad?.let { GlobalHistoryLoadedBanner(it, viewModel::undoHistoryLoad) }
                                }
                            )
                            GlobalPreviewProgressIndicator(
                                visible = previewRefreshing,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                    AdaptivePaneSeparator(paneLayout)
                    Column(Modifier.width(paneLayout.trailingWidth)) {
                        PreviewModeBar(showBefore) { showBefore = it }
                        HorizontalDivider()
                        Box(Modifier.fillMaxSize()) { gridContent() }
                    }
                }
            } else {
                Column(Modifier.weight(1f)) {
                    PreviewModeBar(showBefore) { showBefore = it }
                    HorizontalDivider()
                    Box(Modifier.weight(1f)) { gridContent() }
                    Surface(
                        shape = CardShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val dockMaxHeight = (androidx.compose.ui.platform.LocalConfiguration
                            .current.screenHeightDp * 0.43f).dp
                        Box(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier
                                    .heightIn(max = dockMaxHeight)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 34.dp, height = 4.dp)
                                        .clip(SwatchShape)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                        .align(Alignment.CenterHorizontally)
                                )
                                GlobalStyleControlPanel(
                                    state = state,
                                    categories = categories,
                                    targetCount = targetCount,
                                    activeEffectCount = activeEffectCount,
                                    iconPacks = iconPacks,
                                    resetEnabled = dirty,
                                    onReset = viewModel::resetDraft,
                                    navigation = panel,
                                    onNavigate = openPanel,
                                    loadPackIcons = viewModel::iconPackDropdownIcons,
                                    historyContent = historyContent,
                                    modifier = Modifier.weight(1f, fill = false),
                                    leadingContent = {
                                        viewModel.historyLoad?.let { GlobalHistoryLoadedBanner(it, viewModel::undoHistoryLoad) }
                                    }
                                )
                            }
                            GlobalPreviewProgressIndicator(
                                visible = previewRefreshing,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                }
            }
        }
    }




    if (confirmBake) {
        GlobalStyleBakeDialog(
            onConfirm = {
                confirmBake = false
                view.performConfirmHaptic()
                viewModel.bakeGlobalStyle()
            },
            onDismiss = { confirmBake = false }
        )
    }
    viewModel.history.firstOrNull { it.id == undoBakeId }?.let { item ->
        GlobalStyleUndoBakeDialog(
            item = item,
            onConfirm = {
                undoBakeId = null
                view.performConfirmHaptic()
                viewModel.undoGlobalStyleBake(item)
            },
            onDismiss = { undoBakeId = null }
        )
    }



    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.globalDiscardTitle),
            text = stringResource(R.string.globalDiscardText),
            confirmLabel = stringResource(R.string.editorRecoveryDiscard),
            icon = Icons.Filled.Delete,
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false }
        )
    }

    iconEditor?.let { editor ->
        key(editor.id) {
            GlobalIconEditDialog(editor = editor, onDismiss = { viewModel.dismissIconEditor(editor.id) })
        }
    }
}

/**
 * A section title that stays pinned while its tiles scroll beneath it; the next section's title
 * pushes it up and takes its place. The hint scrolls away with the tiles.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyGridScope.sectionHeader(
    key: String,
    hazeState: HazeState,
    title: @Composable () -> String,
    applies: Boolean,
    hint: (@Composable () -> String)? = null
) {
    stickyHeader(key = key, contentType = "sectionHeader") {
        SectionHeader(title(), applies, hazeState)
    }
    if (hint != null) {
        item(key = "${key}Hint", span = { GridItemSpan(maxLineSpan) }, contentType = "sectionHint") {
            Text(
                text = hint(),
                style = MaterialTheme.typography.bodySmall.copy(shadow = OnWallpaperTextShadow),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/** A frosted bar: the wallpaper shows through its tint and tiles passing beneath are blurred. */
@Composable
private fun SectionHeader(title: String, applies: Boolean, hazeState: HazeState) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val marker = if (applies) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerShape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = surface,
                    tint = HazeTint(surface.copy(alpha = SECTION_HEADER_TINT_ALPHA)),
                    blurRadius = 20.dp
                )
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TargetStatusDot(included = applies, excludedColor = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
        Text(
            text = stringResource(
                if (applies) R.string.globalSectionApplies else R.string.globalSectionSkipped
            ),
            style = MaterialTheme.typography.labelMedium,
            color = marker
        )
    }
}

// Opaque enough to read over any wallpaper, translucent enough to keep the wallpaper visible.
private const val SECTION_HEADER_TINT_ALPHA = 0.7f

/** A filled dot means included; a hollow dot means excluded. */
@Composable
private fun TargetStatusDot(included: Boolean, excludedColor: Color) {
    val indicator = if (included) {
        Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        Modifier.border(1.5.dp, excludedColor, CircleShape)
    }
    Box(Modifier.size(10.dp).then(indicator))
}

/**
 * One preview tile for an app that already has an icon: shows the icon with [options]
 * applied (or as-is when null), the app name, and an edit badge for custom icons.
 */
@Composable
private fun IconPreviewTile(
    app: PackageInfoStruct,
    options: GenerationOptions?,
    viewModel: GlobalOptionsViewModel,
    showEditBadge: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val base = app.baseIcon ?: app.createdIcon
    // Cache at the actual preview size so a recompose never creates a second raster variant.
    val targetPx = with(LocalDensity.current) { iconSize.roundToPx() }
    val baseBitmap = rememberIconBitmap(app, base, iconSize)
    val cached = remember(app.key, app.internalVersion, options, targetPx) {
        options?.let { viewModel.cachedModifiedPreview(app, it, targetPx) }
    }
    // ViewModel-scoped work survives LazyGrid disposing the tile; returning to it reuses the
    // memory-bounded raster cache instead of applying the same modifier again.
    val preview by produceState(cached, app.key, app.internalVersion, options, targetPx) {
        // produceState retains its value when a key changes. Decide from the new cache result,
        // not that retained value, while keeping the previous preview visible during the update.
        if (cached != null || options == null) value = cached
        if (cached == null && base != null && options != null) {
            delay(GLOBAL_PREVIEW_DEBOUNCE_MS)
            value = viewModel.modifiedPreview(app, options, targetPx)
        }
    }
    PreviewTileFrame(app, showEditBadge, iconSize, modifier, onClick) {
        if (preview != null) {
            Image(
                painter = BitmapPainter(preview!!.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        } else if (baseBitmap != null) {
            Image(
                painter = BitmapPainter(baseBitmap),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        }
    }
}

/**
 * A tile for an app with no created icon yet. With the "Without icon" toggle on it previews
 * the icon a generation with the staged globals would produce (the app's launcher icon shows
 * dimmed until that arrives); toggled off it just shows the launcher icon as-is.
 */
@Composable
private fun GeneratedPreviewTile(
    app: PackageInfoStruct,
    sourceOptions: GenerationOptions?,
    modifierOptions: GenerationOptions?,
    viewModel: GlobalOptionsViewModel,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val targetPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val cached = remember(app.key, app.internalVersion, sourceOptions, modifierOptions, targetPx) {
        sourceOptions?.let {
            viewModel.cachedGeneratedPreview(app, it, modifierOptions, targetPx)
        }
    }
    val result by produceState(
        GeneratedPreviewResult(cached, loading = cached == null && sourceOptions != null),
        app.key, app.internalVersion, sourceOptions, modifierOptions, targetPx
    ) {
        value = when {
            sourceOptions == null -> GeneratedPreviewResult(null, loading = false)
            cached != null -> GeneratedPreviewResult(cached, loading = false)
            else -> GeneratedPreviewResult(value.bitmap, loading = true)
        }
        if (cached == null && sourceOptions != null) {
            delay(GLOBAL_PREVIEW_DEBOUNCE_MS)
            value = GeneratedPreviewResult(
                bitmap = viewModel.generatedPreview(
                    app, sourceOptions, modifierOptions, targetPx
                ),
                loading = false
            )
        }
    }
    PreviewTileFrame(app, showEditBadge = false, iconSize = iconSize, modifier = modifier, onClick = onClick) {
        val bitmap = result.bitmap
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        } else {
            val fallback = rememberAppBitmap(app)
            if (fallback != null) {
                Image(
                    painter = BitmapPainter(fallback),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(IconTileShape)
                        // A failed source chain is a final fallback, not a perpetual loading state.
                        .alpha(if (result.loading) 0.35f else 1f)
                )
            }
        }
    }
}

private data class GeneratedPreviewResult(val bitmap: Bitmap?, val loading: Boolean)

/** The shared tile chrome: icon slot on top, single-line app name below, optional edit badge. */
@Composable
private fun PreviewTileFrame(
    app: PackageInfoStruct,
    showEditBadge: Boolean,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(Modifier.size(iconSize)) {
            iconContent()
            if (showEditBadge) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp),
                    shape = SwatchShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
        // Launcher-style label: white with a soft shadow, readable on any wallpaper (the
        // grid sits directly on the transparent, wallpaper-showing area).
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall.copy(shadow = OnWallpaperTextShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = Color.White,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )
    }
}

/**
 * The modifiers-only per-app editor opened from the grid: the full Modifier tab over the
 * app's current icon with a before/after preview. Apply stores the result as a custom icon
 * (a refresh won't replace it), exactly like the main edit dialog's Apply.
 */
@Composable
private fun GlobalIconEditDialog(editor: GlobalIconEditorDraft, onDismiss: () -> Unit) {
    val viewModel: GlobalOptionsViewModel = hiltViewModel()
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val externalEditorError = stringResource(R.string.noImageEditorAvailable)

    val app = editor.app
    val imageEdit = editor.imageEdit
    val iconColor = editor.iconColor
    val useVector = editor.useVector
    val adjustments = editor.adjustments
    val heroBitmap = editor.heroBitmap
    val base = editor.source
    val options = editor.options
    val applyState by viewModel.iconApplyState.collectAsStateWithLifecycle()
    val applying = applyState is GlobalIconApplyState.Running
    LaunchedEffect(applyState, editor.id) {
        (applyState as? GlobalIconApplyState.Finished)?.let { viewModel.consumeIconApply(it.id) }
    }

    // The same pipeline the tile preview uses, so the colour sheets and segment pickers here
    // behave exactly like the edit dialog's.
    val renderWith: suspend (GenerationOptions) -> Bitmap? = { previewOptions ->
        base?.let {
            withContext(Dispatchers.Default) {
                runCatchingCancellable { viewModel.applyModifier(it, previewOptions).toBitmap() }.getOrNull()
            }
        }
    }
    val modifierPreviews = rememberModifierPreviews(
        options = options,
        adjustments = adjustments,
        sourceKey = base,
        render = renderWith
    )

    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val owner = viewModel.screenSession
    val previewState = rememberGlobalIconPreview(
        source = base,
        options = options,
        owner = owner,
        active = runtimeState.ready && owner != null && owner == runtimeState.sessionToken,
        render = viewModel::applyModifier,
        openingOptions = editor.openingOptions
    )
    val preview = previewState.icon
    val generating = previewState.generating
    var enlarged by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss, enabled = !applying) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            view.performConfirmHaptic()
                            val edited = preview
                            if (edited != null && base != null) {
                                viewModel.applyIconEditor(editor.id, options, base, edited)
                            }
                        },
                        enabled = previewState.canApply && !applying,
                        shape = dev.renkinProject.renkin.ui.theme.FieldShape
                    ) {
                        Text(stringResource(R.string.apply))
                    }
                }
                HorizontalDivider()

                // Before/after strip so the modifier's effect is visible while scrolled deep
                // into the controls below.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(56.dp)) {
                        base?.let {
                            Image(
                                painter = it.getPainter(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(IconTileShape)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    // Same affordance as the edit dialog's New slot: tap to judge the result
                    // at a size that actually shows the detail a launcher will.
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clickable(enabled = preview != null) { enlarged = true },
                        contentAlignment = Alignment.Center
                    ) {
                        preview?.let {
                            Image(
                                painter = it.getPainter(),
                                contentDescription = stringResource(R.string.iconNew),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(IconTileShape)
                            )
                        }
                        if (generating) {
                            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                preview?.takeIf { enlarged }?.let { EnlargedIconDialog(it) { enlarged = false } }
                HorizontalDivider()

                ModifierTab(
                    source = Source.APPLICATION_ICON,
                    imageEdit = imageEdit,
                    iconColor = iconColor,
                    useVector = useVector,
                    useMaterialYou = false,
                    adjustments = adjustments,
                    centerPreviewIcon = preview,
                    previewGenerating = generating,
                    sampleBitmap = heroBitmap,
                    previews = modifierPreviews,
                    onImageEditChange = { if (!applying) editor.imageEdit = it },
                    onColorChange = { if (!applying) editor.iconColor = it },
                    onVectorChange = { if (!applying) editor.useVector = it },
                    onMaterialYouChange = { },
                    onEditExternally = { toolbox ->
                        val icon = preview
                        if (icon != null) {
                            scope.launch {
                                val opened = openExternalIconEditor(context, icon, toolbox, viewModel::shareIconForEditing)
                                if (!opened) toaster.show(externalEditorError)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun GlobalBetaDialog(onOpenIssues: () -> Unit, onDismiss: () -> Unit) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Science) },
        title = stringResource(R.string.globalBetaTitle),
        body = AnnotatedString(stringResource(R.string.globalBetaText)),
        actions = {
            DialogPrimaryAction(stringResource(R.string.continueAction), onDismiss)
            DialogSecondaryAction(stringResource(R.string.globalBetaGithub), onOpenIssues)
        }
    )
}
