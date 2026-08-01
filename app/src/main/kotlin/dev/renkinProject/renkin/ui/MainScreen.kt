package dev.renkinProject.renkin.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.ui.theme.IconShape
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.data.AppFilterNoIconKey
import dev.renkinProject.renkin.data.AppSortOrderKey
import dev.renkinProject.renkin.data.getBackgroundColor
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.OnboardingSeenKey
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setEnumValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.GlobalOptionsActivity
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.WatchViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class AppSortOrder { NAME, INSTALL_DATE }

/**
 * The three problem groups the app list can be narrowed to. Multiple groups may be selected;
 * the list then shows their union. A locked app is never also "missing" (see sortedFilteredApps).
 */
enum class AppProblemFilter { MISSING, FALLBACK, LOCKED }

/**
 * Shared app sort/filter overflow: a sort icon button opening a menu with the two sort orders
 * (name / recently installed) and the all-vs-without-icon filter. Used by the home app list and
 * the watch-rule app picker so both look and behave identically.
 */
@Composable
fun AppSortFilterMenu(
    sortOrder: AppSortOrder,
    filterNoIcon: Boolean,
    filterFallback: Boolean = false,
    onSortChange: (AppSortOrder) -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onFallbackFilterChange: (Boolean) -> Unit = {},
    // Missing-pack filter: only offered where locked icons exist (home list).
    filterLocked: Boolean = false,
    showLockedFilter: Boolean = false,
    onLockedFilterChange: (Boolean) -> Unit = {}
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showSortMenu = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.sortApps),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
            CheckableDropdownItem(
                text = stringResource(R.string.sortByName),
                checked = sortOrder == AppSortOrder.NAME
            ) { onSortChange(AppSortOrder.NAME); showSortMenu = false }
            CheckableDropdownItem(
                text = stringResource(R.string.sortByInstallDate),
                checked = sortOrder == AppSortOrder.INSTALL_DATE
            ) { onSortChange(AppSortOrder.INSTALL_DATE); showSortMenu = false }
            HorizontalDivider()
            CheckableDropdownItem(
                text = stringResource(R.string.filterAllApps),
                checked = !filterNoIcon && !filterFallback && !filterLocked
            ) { onFilterChange(false); onFallbackFilterChange(false); onLockedFilterChange(false); showSortMenu = false }
            CheckableDropdownItem(
                text = stringResource(R.string.filterWithoutIcon),
                checked = filterNoIcon
            ) { onFilterChange(!filterNoIcon); showSortMenu = false }
            CheckableDropdownItem(
                text = stringResource(R.string.filterFallback),
                checked = filterFallback
            ) { onFallbackFilterChange(!filterFallback); showSortMenu = false }
            if (showLockedFilter) {
                CheckableDropdownItem(
                    text = stringResource(R.string.filterMissingPack),
                    checked = filterLocked
                ) { onLockedFilterChange(!filterLocked); showSortMenu = false }
            }
        }
    }
}

/**
 * True while the list is scrolling up (or sitting at the top). Used to expand the
 * build FAB on scroll-up and collapse it to an icon on scroll-down.
 */
@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainColumn(iconPacks: List<IconPack>) {
    var packageFilter by remember { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()

    // Scrolling the app list drops the search field's focus (and so dismisses the keyboard),
    // instead of leaving the cursor blinking over the list.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    val prefs = getPreferences()
    val scope = rememberCoroutineScope()
    val sortOrder = prefs.getEnumValue(AppSortOrderKey, AppSortOrder.NAME)
    val storedFilterNoIcon = prefs.getBooleanValue(AppFilterNoIconKey)
    // Mirror the persisted filter locally so switching the hero buttons updates the list in
    // the same frame instead of briefly showing every app while the DataStore write completes.
    var filterNoIcon by rememberSaveable { mutableStateOf(storedFilterNoIcon) }
    LaunchedEffect(storedFilterNoIcon) { filterNoIcon = storedFilterNoIcon }
    // Transient (not a pref): fallback flags only exist after a refresh, so the filter resets too.
    var filterFallback by remember { mutableStateOf(false) }
    // Transient too: locked icons are a temporary condition (install the pack and it's gone).
    var filterLocked by remember { mutableStateOf(false) }

    val activeProblemFilters = buildSet {
        if (filterNoIcon) add(AppProblemFilter.MISSING)
        if (filterFallback) add(AppProblemFilter.FALLBACK)
        if (filterLocked) add(AppProblemFilter.LOCKED)
    }
    // One transition is shared by the overflow menu and hero button group. Each problem filter
    // remains independent; only the Missing choice is persisted because the others are transient.
    val setProblemFilter: (AppProblemFilter, Boolean) -> Unit = { filter, enabled ->
        when (filter) {
            AppProblemFilter.MISSING -> {
                filterNoIcon = enabled
                scope.launch { prefs.setBooleanValue(AppFilterNoIconKey, enabled) }
            }
            AppProblemFilter.FALLBACK -> filterFallback = enabled
            AppProblemFilter.LOCKED -> filterLocked = enabled
        }
    }
    val toggleProblemFilter: (AppProblemFilter) -> Unit = { filter ->
        setProblemFilter(filter, filter !in activeProblemFilters)
    }
    val clearProblemFilters: () -> Unit = {
        setProblemFilter(AppProblemFilter.MISSING, false)
        setProblemFilter(AppProblemFilter.FALLBACK, false)
        setProblemFilter(AppProblemFilter.LOCKED, false)
    }

    // Require a second back press to leave. Registered here (before the search bar),
    // so the search bar's clear-on-back handler takes priority while it has text.
    val context = LocalContext.current
    val activity = getCurrentMainActivity()
    val viewModel: MainViewModel = hiltViewModel()
    val isInRefresh = viewModel.isRefreshing

    // Forward one-shot toast events from the ViewModel to the shared Toaster. A single
    // collector here covers every VM-originated toast (build installed, packs synced,
    // app list refreshed) regardless of which dialog/button triggered it.
    val toaster = LocalToaster.current
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { resId -> toaster.show(context.getString(resId)) }
    }

    // Undoable changes get a snackbar instead of a toast: a toast cannot be acted on, and the
    // whole point is the action. Only the newest offer is on screen, matching the single step
    // the provider keeps.
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undoAction)
    LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { prompt ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = context.getString(prompt.messageRes, prompt.count),
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoLastIconChange()
        }
    }

    // An external icon pack was installed while the app was open → offer to reload so it
    // shows up among the available icon-pack sources.
    val newIconPack = viewModel.newIconPackInstalled
    if (newIconPack != null) {
        NewIconPackDialog(
            packLabel = newIconPack,
            onReload = {
                viewModel.sync()
                viewModel.dismissNewIconPack()
            },
            onDismiss = { viewModel.dismissNewIconPack() }
        )
    }

    if (viewModel.installFallbackPending) {
        RenkinAlertDialog(
            onDismissRequest = { viewModel.dismissInstallFallback() },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.installFallbackTitle)) },
            text = { Text(boldStringResource(R.string.installFallbackText)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmInstallFallback() }) {
                    Text(stringResource(R.string.installFallbackAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissInstallFallback() }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }

    // Post-build next steps: launchers need a nudge to pick up a (re)built pack, and the nudge
    // differs between a first install (pick the pack) and an update (switch away and back).
    val buildOutcome = viewModel.buildOutcome
    if (buildOutcome != null) {
        val isUpdate = buildOutcome.outcome == MainViewModel.BuildOutcome.UPDATE
        RenkinAlertDialog(
            onDismissRequest = { viewModel.dismissBuildOutcome() },
            icon = {
                Icon(
                    imageVector = if (isUpdate) Icons.Filled.Refresh else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(stringResource(
                    if (isUpdate) R.string.buildUpdatedTitle else R.string.buildInstalledTitle
                ))
            },
            text = {
                Text(boldStringResource(
                    if (isUpdate) R.string.buildUpdatedText else R.string.buildInstalledText,
                    buildOutcome.packLabel
                ))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissBuildOutcome() }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    val pressBackMessage = stringResource(R.string.pressBackToExit)
    var lastBackPress by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            activity.finish()
        } else {
            lastBackPress = now
            toaster.show(pressBackMessage)
        }
    }

    // The pinned search bar sits right under the top bar, so it tracks the same
    // scrolled tint — otherwise the header looks split (tinted bar, white search)
    val headerScrolled by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction > 0.01f }
    }
    val headerColor by animateColorAsState(
        targetValue = if (headerScrolled) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.background
        },
        label = "searchBarBackground"
    )

    // No pull-to-refresh here on purpose: its nested-scroll handler interfered with the
    // collapsing top bar (scroll glitches, freezes) — the app list reloads from Settings.
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TitleBar(scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = { BuildPackFab(isInRefresh, expanded = listState.isScrollingUp()) }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            SearchBar(
                containerColor = headerColor,
                sortOrder = sortOrder,
                filterNoIcon = filterNoIcon,
                filterFallback = filterFallback,
                filterLocked = filterLocked,
                showLockedFilter = viewModel.lockedIconKeys.isNotEmpty() || filterLocked,
                onSortChange = { scope.launch { prefs.setEnumValue(AppSortOrderKey, it) } },
                onFilterChange = { setProblemFilter(AppProblemFilter.MISSING, it) },
                onFallbackFilterChange = { setProblemFilter(AppProblemFilter.FALLBACK, it) },
                onLockedFilterChange = { setProblemFilter(AppProblemFilter.LOCKED, it) },
                onSearch = { packageFilter = it }
            )
            ApplicationList(
                iconPacks, packageFilter, sortOrder, filterNoIcon, filterFallback, filterLocked, listState,
                onShowAllApps = clearProblemFilters,
                activeProblemFilters = activeProblemFilters,
                onProblemFilterToggle = toggleProblemFilter
            )
        }
    }

    // First-run intro: shows until dismissed once; Settings → "Show intro" clears the flag
    // to bring it back. Initial=true so nothing flashes while the DataStore loads.
    val onboardingSeen by remember {
        prefs.data.map { it.getBooleanValue(OnboardingSeenKey) }
    }.collectAsState(initial = true)
    if (!onboardingSeen) {
        OnboardingOverlay {
            scope.launch { prefs.setBooleanValue(OnboardingSeenKey, true) }
        }
    }

    // Opened from an icon-watch notification → show the apply modal for that suggestion
    val pendingSuggestion = viewModel.pendingWatchSuggestionId
    if (pendingSuggestion != null) {
        WatchApplyModal(pendingSuggestion) { viewModel.clearPendingWatchSuggestion() }
    }

    // The replace-everything confirmation for a picked full-backup file. Hosted here (not in
    // Settings) so imports started from the profile switcher confirm too.
    if (viewModel.pendingBackupImport != null) {
        ConfirmDialog(
            title = stringResource(R.string.importBackupTitle),
            text = stringResource(R.string.importBackupText),
            icon = Icons.Filled.Restore,
            onConfirm = { viewModel.confirmBackupImport() },
            onDismiss = { viewModel.cancelBackupImport() }
        )
    }

    // Locked icons from a missing pack: prompted automatically once per profile per session
    // (unless the profile opted out); the top-bar badge and banner reopen it any time.
    if (viewModel.showMissingPacksDialog) {
        MissingPacksDialog(viewModel.missingPackSummary) { dontShowAgain ->
            viewModel.dismissMissingPacksDialog(dontShowAgain)
        }
    }

    // The notification pointed at a profile that no longer exists — explain instead of
    // silently doing nothing (or worse, applying the icon to the wrong profile).
    if (viewModel.watchProfileMissing) {
        RenkinAlertDialog(
            onDismissRequest = { viewModel.clearWatchProfileMissing() },
            icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.watchProfileGoneTitle)) },
            text = { Text(boldStringResource(R.string.watchProfileGoneText)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearWatchProfileMissing() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

/**
 * Prompts the user to reload after an external icon pack was installed while the app was
 * open, so the new pack appears among the available sources. [onReload] re-syncs the packs.
 */
@Composable
fun NewIconPackDialog(packLabel: String, onReload: () -> Unit, onDismiss: () -> Unit) {
    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.newIconPackTitle)) },
        text = { Text(boldStringResource(R.string.newIconPackText, packLabel)) },
        confirmButton = {
            TextButton(onClick = onReload) { Text(stringResource(R.string.reload)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApplicationList(
    iconPacks: List<IconPack>,
    filter: String,
    sortOrder: AppSortOrder,
    filterNoIcon: Boolean,
    filterFallback: Boolean = false,
    filterLocked: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    // Clears the active icon filters; offered on the empty state so a filtered-out list has an
    // obvious way back. Null hides the button (e.g. when no filter could be the cause).
    onShowAllApps: (() -> Unit)? = null,
    // Passed straight through to the hero card's problem toggles; a null callback hides them.
    activeProblemFilters: Set<AppProblemFilter> = emptySet(),
    onProblemFilterToggle: ((AppProblemFilter) -> Unit)? = null
) {
    val viewModel: MainViewModel = hiltViewModel()
    val applications = viewModel.applicationList

    // Read preferences once for the whole list — a DataStore subscription per row
    // causes visible scroll jank
    val prefs = getPreferences()
    val bgColorValue = prefs.getBackgroundColor()
    val themed = prefs.getBooleanValue(ExportThemedKey)

    // Install times refresh whenever the set of packages changes — keying on size alone missed
    // a reinstall or list refresh that swapped apps without changing the count. Looked up off
    // the main thread via the view model — until they arrive, INSTALL_DATE sort just shows the
    // default order.
    val packageNames by remember(applications) {
        derivedStateOf {
            applications.mapTo(linkedSetOf()) { it.packageName }.toList()
        }
    }
    val installTimes by produceState(emptyMap<String, Long>(), packageNames) {
        value = viewModel.installTimes(packageNames)
    }

    // Keep the original index — ApplicationItem edits the app list by position (via the VM).
    // derivedStateOf so it recomputes when the list's contents change (applicationList is a
    // SnapshotStateList edited in place, so its instance identity never changes) while still
    // caching across unrelated recompositions. Recreated when the sort/filter inputs change.
    val displayList by remember(sortOrder, filterNoIcon, filterFallback, filterLocked, filter, installTimes) {
        derivedStateOf {
            // withIndex() first so each app keeps its original position; the shared pipeline then
            // filters and sorts the wrappers (selector pulls the app out of each IndexedValue).
            applications.withIndex().toList()
                .sortedFilteredApps(
                    filter, filterNoIcon, filterFallback, sortOrder, installTimes,
                    filterLocked, viewModel.lockedIconKeys
                ) { it.value }
        }
    }

    // Keyed items make LazyColumn follow the previously visible app to its new
    // position when the order changes — jump back to the top instead
    LaunchedEffect(sortOrder, filterNoIcon, filterFallback, filterLocked) {
        listState.scrollToItem(0)
    }

    // Global options runs in its own activity (windowShowWallpaper — the icon grid previews
    // over the real wallpaper). Its result carries the session bookkeeping back: hand-edited
    // keys and whether a Save persisted the profile.
    val globalOptionsContext = LocalContext.current
    val globalOptionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val edited = result.data
            ?.getStringArrayListExtra(GlobalOptionsActivity.EXTRA_EDITED_KEYS)
            ?.toSet() ?: emptySet()
        val applied = result.data
            ?.getBooleanExtra(GlobalOptionsActivity.EXTRA_GLOBAL_APPLIED, false) ?: false
        if (edited.isNotEmpty() || applied) viewModel.onGlobalOptionsClosed(edited, applied)
    }

    // Startup loads apps, then icon packs, then the profile's saved icons — all off the main
    // thread. Hold the spinner until ALL of it is done: revealing the list earlier shows rows
    // whose icons pop in seconds later, because the per-row bitmap decodes queue behind the
    // pack/DB loading still running on the same worker pool.
    if (!viewModel.startupComplete) {
        if (viewModel.startupFailed) {
            EmptyState(
                icon = Icons.Filled.Warning,
                text = stringResource(R.string.startupLoadFailed),
                modifier = Modifier.fillMaxSize(),
                actionLabel = stringResource(R.string.reload),
                onAction = viewModel::retryStartup
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.drawVerticalScrollbar(listState),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Missing-pack banner rides above the hero card while anything stays locked.
        if (viewModel.missingPackSummary.isNotEmpty()) {
            item(key = "missingPacks", contentType = "missingPacks") {
                MissingPacksBanner(
                    packCount = viewModel.missingPackSummary.size,
                    iconCount = viewModel.missingPackSummary.sumOf { it.iconCount },
                    onClick = { viewModel.openMissingPacksDialog() }
                )
            }
        }
        // Scrolls away with the list — only the search bar stays pinned
        item(key = "hero", contentType = "hero") {
            HeroPackCard(iconPacks, activeProblemFilters, onProblemFilterToggle)
        }
        item(key = "options", contentType = "options") {
            AdvancedOptionsCard(iconPacks) {
                globalOptionsLauncher.launch(
                    Intent(globalOptionsContext, GlobalOptionsActivity::class.java)
                )
            }
        }
        if (displayList.isEmpty()) {
            // A filter/search matched nothing — say so instead of leaving a blank gap.
            item(key = "empty", contentType = "empty") {
                // Offer the way out when an icon filter is what emptied the list.
                val filtered = filterNoIcon || filterFallback || filterLocked
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    text = stringResource(R.string.noAppsFound),
                    modifier = Modifier
                        .fillParentMaxHeight(0.6f)
                        .fillMaxWidth(),
                    actionLabel = stringResource(R.string.filterAllApps).takeIf { filtered && onShowAllApps != null },
                    onAction = onShowAllApps.takeIf { filtered }
                )
            }
        } else {
            items(displayList, key = { it.value.key }, contentType = { "application" }) { indexedApp ->
                ApplicationItem(iconPacks, indexedApp.value, indexedApp.index, themed, bgColorValue, Modifier.animateItem())
            }
        }
    }
}

/**
 * Wraps a small badge so long-pressing (or hovering) it shows a plain tooltip explaining what it
 * means — the badges are otherwise cryptic. [modifier] carries the badge's alignment in its parent.
 */
@Composable
internal fun BadgeTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // [modifier] (the badge's alignment) goes on a wrapping Box that sizes to the badge — putting
    // it on the tooltip box directly leaves the badge centered.
    Box(modifier) {
        RenkinTooltipBox(text) {
            content()
        }
    }
}

@Composable
fun ApplicationItem(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    index: Int,
    themed: Boolean,
    bgColorValue: Color,
    modifier: Modifier = Modifier
) {
    val viewModel: MainViewModel = hiltViewModel()
    val dynamicColor = themed && supportDynamicColors()
    val view = LocalView.current
    val toaster = LocalToaster.current
    val syncWarning = stringResource(id = R.string.syncText)

    var openAppOptions by rememberSaveable { mutableStateOf(false) }
    var quickActionsOpen by rememberSaveable { mutableStateOf(false) }

    // Closing the edit dialog (whose icon-search field had keyboard focus) otherwise lets the
    // system hand focus to the home search field, popping the keyboard. Clear focus only on the
    // actual open→close transition — not on initial composition, or filtering the list (which
    // recomposes rows) would clear the search field's focus after every keystroke.
    val focusManager = LocalFocusManager.current
    var wasOptionsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openAppOptions) {
        if (wasOptionsOpen && !openAppOptions) focusManager.clearFocus(force = true)
        wasOptionsOpen = openAppOptions
    }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // A long press opens the quick actions; the tap keeps opening the full editor, so
            // nothing that worked before needs relearning.
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    view.performTapHaptic()
                    if (viewModel.iconPackLoaded) {
                        openAppOptions = true
                    } else {
                        toaster.show(syncWarning)
                    }
                },
                onLongClick = {
                    view.performLongPressHaptic()
                    quickActionsOpen = true
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Decoded once at the on-screen size (56.dp) by the shared helper, so scrolling
            // new rows in doesn't decode oversized bitmaps on the main thread (scroll jank).
            val bitmap = rememberAppBitmap(app, 56.dp)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(IconShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (bitmap != null) {
                    Image(
                        painter = BitmapPainter(bitmap),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            val bgColor = if (themed) {
                if (dynamicColor) {
                    colorResource(R.color.icon_background_color)
                } else {
                    bgColorValue
                }
            } else {
                Color.Unspecified
            }

            // Crossfade the trailing slot so assigning or clearing an icon fades
            // between the preview and the edit bubble instead of popping
            Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                val isLocked = app.createdIcon == null && app.key in viewModel.lockedIconKeys
                // Rasterised at the row's size: a vector painter grows into place over the first
                // frames, which read as the icon jumping after it appeared.
                val createdBitmap = rememberCreatedIconBitmap(app, 56.dp)
                // Fixed frame for every state: the edit bubble is smaller than an icon, and
                // letting Crossfade shrink the slot around it slid the bubble into place after
                // the fade. Each branch centres itself inside the same 56.dp box instead.
                Crossfade(
                    targetState = createdBitmap,
                    label = "iconPreview",
                    modifier = Modifier.size(56.dp)
                ) { preview ->
                  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (preview != null) {
                        Image(
                            painter = BitmapPainter(preview),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(IconShape)
                                .background(bgColor)
                        )
                    } else if (app.createdIcon != null) {
                        // Has an icon, still rasterising: an empty slot for a frame beats
                        // flashing the "no icon" bubble on every list load.
                        Box(Modifier.size(56.dp))
                    } else if (isLocked) {
                        // The saved icon exists but its source pack is missing/paid — show a
                        // dashed placeholder; tapping the row still lets the user pick another.
                        val outline = MaterialTheme.colorScheme.outline
                        RenkinTooltipBox(stringResource(R.string.lockedIconTooltip)) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .drawBehind {
                                        drawRoundRect(
                                            color = outline,
                                            style = Stroke(
                                                width = 1.5.dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                            ),
                                            cornerRadius = CornerRadius(16.dp.toPx())
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.lockedIconTooltip),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.edit),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                  }
                }
                // Calendar day badge: shows today's date so the user can see the icon rotates.
                if (app.calendarEnabled) {
                    val today = remember { java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) }
                    BadgeTooltip(stringResource(R.string.calendarBadgeTooltip), Modifier.align(Alignment.BottomEnd)) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = today.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                // Fallback badge: this icon came from the pack's fallback styling, not a real match.
                if (app.isFallback) {
                    BadgeTooltip(stringResource(R.string.fallbackBadgeTooltip), Modifier.align(Alignment.TopStart)) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoFixHigh,
                                contentDescription = stringResource(R.string.fallbackIcon),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (quickActionsOpen) {
        AppQuickActionsSheet(app = app, onDismiss = { quickActionsOpen = false })
    }

    if (openAppOptions) {
        OpenAppOptions(iconPacks, app, themed) {
            openAppOptions = false
        }
    }
}

@Composable
fun OpenAppOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    themed: Boolean,
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()

    OptionsDialog(iconPacks, app, themed, { icon, calendarEnabled, calendarPrefix, calendarPackName, sourcePackName, sourceUrl ->
        viewModel.applyIcon(app, icon, calendarEnabled, calendarPrefix, calendarPackName, sourcePackName, sourceUrl)
        onDismiss()
    }, {
        onDismiss()
    }) {
        onDismiss()
        viewModel.resetIcon(app)
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun RefreshButton() {
    val viewModel: MainViewModel = hiltViewModel()
    val isRefreshing = viewModel.isRefreshing

    IconButton(enabled = !isRefreshing, onClick = viewModel::refresh) {
        if (isRefreshing) {
            LoadingIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.refreshIcons),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TitleBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val prefs = getPreferences()
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }
    var openWatch by rememberSaveable { mutableStateOf(false) }

    val watchViewModel: WatchViewModel = hiltViewModel()
    val completedCount by watchViewModel.completedCount.collectAsState()
    val mainViewModel: MainViewModel = hiltViewModel()

    LargeFlexibleTopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            ProfileSwitcherTitle()
        },
        actions = {
            // Lit while any of the active profile's icons are locked behind a missing pack.
            // Error tint on purpose: it must stand out from the primary-tinted actions.
            if (mainViewModel.missingPackSummary.isNotEmpty()) {
                IconButton(onClick = { mainViewModel.openMissingPacksDialog() }) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(R.string.missingPacksTitle),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            RefreshButton()
            IconButton(onClick = { openWatch = true }) {
                BadgedBox(badge = {
                    if (completedCount > 0) {
                        Badge { Text(completedCount.toString()) }
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.openWatchedIcons),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = { openInfo = true }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.info),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { openSettings = true }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )

    if (openSettings) {
        SettingsScreen(prefs) {
            openSettings = false
        }
    }

    if (openInfo) {
        InfoDialog {
            openInfo = false
        }
    }

    if (openWatch) {
        WatchScreen {
            openWatch = false
        }
    }
}

@Composable
fun SearchBar(
    sortOrder: AppSortOrder,
    filterNoIcon: Boolean,
    filterFallback: Boolean = false,
    filterLocked: Boolean = false,
    showLockedFilter: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.background,
    onSortChange: (AppSortOrder) -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onFallbackFilterChange: (Boolean) -> Unit = {},
    onLockedFilterChange: (Boolean) -> Unit = {},
    onSearch: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Back clears the query and drops focus first; only once it's empty does back fall through
    // to the exit handler above.
    BackHandler(enabled = text.isNotEmpty()) {
        text = ""
        onSearch("")
        focusManager.clearFocus()
    }

    Surface(color = containerColor, modifier = Modifier.fillMaxWidth()) {
        // The sort/filter menu lives INSIDE the field as its trailing control — beside the
        // field it looked like a stray icon with no surface of its own.
        SearchField(
            value = text,
            onValueChange = {
                text = it
                onSearch(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            extraTrailing = {
                AppSortFilterMenu(
                    sortOrder, filterNoIcon, filterFallback, onSortChange, onFilterChange, onFallbackFilterChange,
                    filterLocked = filterLocked,
                    showLockedFilter = showLockedFilter,
                    onLockedFilterChange = onLockedFilterChange
                )
            }
        )
    }
}
