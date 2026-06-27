package dev.alembiconsProject.alembicons.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.packages.supportDynamicColors
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.ui.theme.CardShape
import dev.alembiconsProject.alembicons.data.AppFilterNoIconKey
import dev.alembiconsProject.alembicons.data.AppSortOrderKey
import dev.alembiconsProject.alembicons.data.getBackgroundColor
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.getPreferencesValue
import dev.alembiconsProject.alembicons.data.setBooleanValue
import dev.alembiconsProject.alembicons.data.setEnumValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.alembiconsProject.alembicons.MainViewModel
import dev.alembiconsProject.alembicons.WatchViewModel
import kotlinx.coroutines.launch

enum class AppSortOrder { NAME, INSTALL_DATE }

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
    onFallbackFilterChange: (Boolean) -> Unit = {}
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CheckableDropdownItem(
                text = stringResource(R.string.filterAllApps),
                checked = !filterNoIcon && !filterFallback
            ) { onFilterChange(false); onFallbackFilterChange(false); showSortMenu = false }
            CheckableDropdownItem(
                text = stringResource(R.string.filterWithoutIcon),
                checked = filterNoIcon
            ) { onFilterChange(true); showSortMenu = false }
            CheckableDropdownItem(
                text = stringResource(R.string.filterFallback),
                checked = filterFallback
            ) { onFallbackFilterChange(true); showSortMenu = false }
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

    val prefs = getPreferences()
    val scope = rememberCoroutineScope()
    val sortOrder = prefs.getEnumValue(AppSortOrderKey, AppSortOrder.NAME)
    val filterNoIcon = prefs.getBooleanValue(AppFilterNoIconKey)
    // Transient (not a pref): fallback flags only exist after a refresh, so the filter resets too.
    var filterFallback by remember { mutableStateOf(false) }

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

    val pressBackMessage = stringResource(R.string.pressBackToExit)
    var lastBackPress by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            activity.finish()
        } else {
            lastBackPress = now
            Toast.makeText(context, pressBackMessage, Toast.LENGTH_SHORT).show()
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TitleBar(scrollBehavior) },
        floatingActionButton = { BuildPackFab(isInRefresh, expanded = listState.isScrollingUp()) }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            SearchBar(
                containerColor = headerColor,
                sortOrder = sortOrder,
                filterNoIcon = filterNoIcon,
                filterFallback = filterFallback,
                onSortChange = { scope.launch { prefs.setEnumValue(AppSortOrderKey, it) } },
                onFilterChange = {
                    filterFallback = false
                    scope.launch { prefs.setBooleanValue(AppFilterNoIconKey, it) }
                },
                onFallbackFilterChange = {
                    filterFallback = it
                    if (it) scope.launch { prefs.setBooleanValue(AppFilterNoIconKey, false) }
                },
                onSearch = { packageFilter = it }
            )
            ApplicationList(iconPacks, packageFilter, sortOrder, filterNoIcon, filterFallback, listState)
        }
    }

    // Opened from an icon-watch notification → show the apply modal for that suggestion
    val pendingSuggestion = viewModel.pendingWatchSuggestionId
    if (pendingSuggestion != null) {
        WatchApplyModal(pendingSuggestion) { viewModel.clearPendingWatchSuggestion() }
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
        title = { Text(stringResource(R.string.newIconPackTitle)) },
        text = { Text(stringResource(R.string.newIconPackText, packLabel)) },
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
    listState: LazyListState = rememberLazyListState()
) {
    val viewModel: MainViewModel = hiltViewModel()
    val applications = viewModel.applicationList

    // Read preferences once for the whole list — a DataStore subscription per row
    // causes visible scroll jank
    val prefs = getPreferences()
    val bgColorValue = prefs.getBackgroundColor()
    val themed = prefs.getBooleanValue(ExportThemedKey)

    // Install times do not change while the app runs; refresh only when the list grows/shrinks.
    // Looked up off the main thread via the view model — until they arrive, INSTALL_DATE sort
    // just shows the default order.
    val installTimes by produceState(emptyMap<String, Long>(), applications.size) {
        value = viewModel.installTimes(applications.map { it.packageName })
    }

    // Keep the original index — ApplicationItem edits the app list by position (via the VM).
    // derivedStateOf so it recomputes when the list's contents change (applicationList is a
    // SnapshotStateList edited in place, so its instance identity never changes) while still
    // caching across unrelated recompositions. Recreated when the sort/filter inputs change.
    val displayList by remember(sortOrder, filterNoIcon, filterFallback, filter, installTimes) {
        derivedStateOf {
            when (sortOrder) {
                AppSortOrder.NAME -> applications.withIndex().toList()
                AppSortOrder.INSTALL_DATE -> applications.withIndex()
                    .sortedByDescending { installTimes[it.value.packageName] ?: 0L }
            }.let { list ->
                when {
                    filterFallback -> list.filter { it.value.isFallback }
                    filterNoIcon -> list.filter { it.value.createdIcon == null }
                    else -> list
                }
            }.let { list ->
                // Filter before the LazyColumn so non-matching rows don't become empty items
                if (filter.isEmpty()) list else list.filter {
                    it.value.appName.contains(filter, true) || it.value.originalName.contains(filter, true)
                }
            }
        }
    }

    // Keyed items make LazyColumn follow the previously visible app to its new
    // position when the order changes — jump back to the top instead
    LaunchedEffect(sortOrder, filterNoIcon, filterFallback) {
        listState.scrollToItem(0)
    }

    // The app list is loaded off the main thread at startup; show a spinner until
    // it arrives instead of a blank screen that looks frozen
    if (!viewModel.applicationsLoaded && applications.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Scrolls away with the list — only the search bar stays pinned
        item(key = "options") {
            OptionsCard(iconPacks)
        }
        if (displayList.isEmpty()) {
            // A filter/search matched nothing — say so instead of leaving a blank gap.
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    text = stringResource(R.string.noAppsFound),
                    modifier = Modifier
                        .fillParentMaxHeight(0.6f)
                        .fillMaxWidth()
                )
            }
        } else {
            items(displayList, key = { it.value.key }) { indexedApp ->
                ApplicationItem(iconPacks, indexedApp.value, indexedApp.index, themed, bgColorValue, Modifier.animateItem())
            }
        }
    }
}

/**
 * Wraps a small badge so long-pressing (or hovering) it shows a plain tooltip explaining what it
 * means — the badges are otherwise cryptic. [modifier] carries the badge's alignment in its parent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BadgeTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // [modifier] (the badge's alignment) goes on a wrapping Box that sizes to the badge — putting
    // it on TooltipBox directly leaves the badge centered. TooltipDefaults' position provider then
    // keeps the popup inside the window on its own.
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(text) } },
            state = rememberTooltipState()
        ) {
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
        onClick = {
            view.performTapHaptic()
            if (viewModel.iconPackLoaded) {
                openAppOptions = true
            } else {
                toaster.show(syncWarning)
            }
        },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
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
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
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
                Crossfade(targetState = app.createdIcon, label = "iconPreview") { createdIcon ->
                    if (createdIcon != null) {
                        Image(
                            painter = createdIcon.getPainter(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(bgColor)
                        )
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

    if (openAppOptions) {
        OpenAppOptions(iconPacks, app, themed, index) {
            openAppOptions = false
        }
    }
}

@Composable
fun OpenAppOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    themed: Boolean,
    index: Int,
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()

    AppOptions(iconPacks, app, themed, { icon, calendarEnabled, calendarPrefix, calendarPackName, sourcePackName ->
        viewModel.applyIcon(index, app, icon, calendarEnabled, calendarPrefix, calendarPackName, sourcePackName)
        onDismiss()
    }, {
        onDismiss()
    }) {
        onDismiss()
        viewModel.applyIcon(index, app, null)
    }
}

@Composable
fun RefreshButton() {
    val preferences = getPreferences().getPreferencesValue()
    val viewModel: MainViewModel = hiltViewModel()
    val toaster = LocalToaster.current
    val syncWarning = stringResource(id = R.string.syncText)

    IconButton(onClick = {
        if (!viewModel.refresh(preferences)) {
            toaster.show(syncWarning)
        }
    }) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = stringResource(R.string.refreshIcons),
            tint = MaterialTheme.colorScheme.primary
        )
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

    LargeFlexibleTopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text(stringResource(id = R.string.app_name))
        },
        actions = {
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
        SettingsDialog(prefs) {
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
    containerColor: Color = MaterialTheme.colorScheme.background,
    onSortChange: (AppSortOrder) -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onFallbackFilterChange: (Boolean) -> Unit = {},
    onSearch: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    // Back clears the query first; only once it's empty does back fall through to
    // the exit handler above (the keyboard, if open, is dismissed by the system first)
    BackHandler(enabled = text.isNotEmpty()) {
        text = ""
        onSearch("")
    }

    Surface(color = containerColor, modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SearchField(
            value = text,
            onValueChange = {
                text = it
                onSearch(it)
            },
            modifier = Modifier.weight(1f)
        )
        AppSortFilterMenu(sortOrder, filterNoIcon, filterFallback, onSortChange, onFilterChange, onFallbackFilterChange)
    }
    }
}

