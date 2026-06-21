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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.AppFilterNoIconKey
import dev.alembiconsProject.alembicons.data.AppSortOrderKey
import dev.alembiconsProject.alembicons.data.BackgroundColorKey
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getColorValue
import dev.alembiconsProject.alembicons.data.getDefaultBackgroundColor
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.getPreferencesValue
import dev.alembiconsProject.alembicons.data.setBooleanValue
import dev.alembiconsProject.alembicons.data.setEnumValue
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import dev.alembiconsProject.alembicons.MainViewModel
import kotlinx.coroutines.launch

enum class AppSortOrder { NAME, INSTALL_DATE }

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

    // Require a second back press to leave. Registered here (before the search bar),
    // so the search bar's clear-on-back handler takes priority while it has text.
    val context = LocalContext.current
    val activity = getCurrentMainActivity()
    val viewModel: MainViewModel = hiltViewModel()
    val isInRefresh = viewModel.isRefreshing
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
                onSortChange = { scope.launch { prefs.setEnumValue(AppSortOrderKey, it) } },
                onFilterChange = { scope.launch { prefs.setBooleanValue(AppFilterNoIconKey, it) } },
                onSearch = { packageFilter = it }
            )
            ApplicationList(iconPacks, packageFilter, sortOrder, filterNoIcon, listState)
        }
    }

    // Opened from an icon-watch notification → show the apply modal for that suggestion
    val pendingSuggestion = viewModel.pendingWatchSuggestionId
    if (pendingSuggestion != null) {
        WatchApplyModal(pendingSuggestion) { viewModel.clearPendingWatchSuggestion() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApplicationList(
    iconPacks: List<IconPack>,
    filter: String,
    sortOrder: AppSortOrder,
    filterNoIcon: Boolean,
    listState: LazyListState = rememberLazyListState()
) {
    val viewModel: MainViewModel = hiltViewModel()
    val pm = LocalContext.current.packageManager
    val applications = viewModel.appProvider.applicationList

    // Read preferences once for the whole list — a DataStore subscription per row
    // causes visible scroll jank
    val prefs = getPreferences()
    val bgColorValue = prefs.getColorValue(BackgroundColorKey, prefs.getDefaultBackgroundColor())
    val themed = prefs.getBooleanValue(ExportThemedKey)

    // Install times do not change while the app runs; refresh only when the list grows/shrinks
    val installTimes = remember(applications.size) {
        applications.associate { app ->
            app.packageName to try {
                pm.getPackageInfo(app.packageName, 0).firstInstallTime
            } catch (_: Exception) {
                0L
            }
        }
    }

    // Keep the original index — ApplicationItem edits appProvider.applicationList by position.
    // Remembered so it isn't re-sorted/filtered on unrelated recompositions.
    val displayList = remember(applications, sortOrder, filterNoIcon, filter, installTimes) {
        when (sortOrder) {
            AppSortOrder.NAME -> applications.withIndex().toList()
            AppSortOrder.INSTALL_DATE -> applications.withIndex()
                .sortedByDescending { installTimes[it.value.packageName] ?: 0L }
        }.let { list ->
            if (filterNoIcon) list.filter { it.value.createdIcon == null } else list
        }.let { list ->
            // Filter before the LazyColumn so non-matching rows don't become empty items
            if (filter.isEmpty()) list else list.filter { it.value.appName.contains(filter, true) }
        }
    }

    // Keyed items make LazyColumn follow the previously visible app to its new
    // position when the order changes — jump back to the top instead
    LaunchedEffect(sortOrder, filterNoIcon) {
        listState.scrollToItem(0)
    }

    // The app list is loaded off the main thread at startup; show a spinner until
    // it arrives instead of a blank screen that looks frozen
    if (!viewModel.appProvider.applicationsLoaded && applications.isEmpty()) {
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
        items(displayList, key = { "${it.value.packageName}/${it.value.activityName}" }) { indexedApp ->
            ApplicationItem(iconPacks, indexedApp.value, indexedApp.index, themed, bgColorValue, Modifier.animateItem())
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

    var openAppOptions by rememberSaveable { mutableStateOf(false) }
    var openWarning by rememberSaveable { mutableStateOf(false) }

    Surface(
        onClick = {
            view.performTapHaptic()
            if (viewModel.appProvider.iconPackLoaded) {
                openAppOptions = true
            } else {
                openWarning = true
            }
        },
        shape = RoundedCornerShape(20.dp),
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
            // Converting the drawable is not free — cache it, and render at the on-screen
            // size (56.dp) rather than full native resolution so scrolling new rows into
            // view doesn't decode oversized bitmaps on the main thread (scroll jank).
            val density = LocalDensity.current
            val bitmap = remember(app.icon, density) {
                val target = with(density) { 56.dp.roundToPx() }
                val size = app.icon.intrinsicWidth.let { if (it in 1 until target) it else target }
                app.icon.toSafeBitmapOrNull(size, size)
            }
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap.asImageBitmap()),
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
                                    contentDescription = "Edit",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
    
    if (openWarning) {
        ShowToast(stringResource(id = R.string.syncText))
        openWarning = false
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

    AppOptions(iconPacks, app, themed, { icon ->
        viewModel.applyIcon(index, app, icon)
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

    var openWarning by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = {
        if (!viewModel.refresh(preferences)) {
            openWarning = true
        }
    }) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh icons",
            tint = MaterialTheme.colorScheme.primary
        )
    }

    if (openWarning) {
        ShowToast(stringResource(id = R.string.syncText))
        openWarning = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TitleBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val prefs = getPreferences()
    val context = getCurrentContext()
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }
    var openWatch by rememberSaveable { mutableStateOf(false) }

    val watchRepo = remember { dev.alembiconsProject.alembicons.data.watch.WatchRepository(context) }
    val completedCount by watchRepo.completedCount.collectAsState(initial = 0)

    LargeFlexibleTopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
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
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { openSettings = true }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
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
    containerColor: Color = MaterialTheme.colorScheme.background,
    onSortChange: (AppSortOrder) -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onSearch: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }

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
        OutlinedTextField(value = text,
            onValueChange = {
                text = it
                onSearch(it)
            },
            shape = CircleShape,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )},
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        onSearch(text)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }},
            modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(
                    imageVector = Icons.Filled.Sort,
                    contentDescription = stringResource(R.string.sortApps),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sortByName)) },
                    onClick = { onSortChange(AppSortOrder.NAME); showSortMenu = false },
                    leadingIcon = if (sortOrder == AppSortOrder.NAME) {
                        { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sortByInstallDate)) },
                    onClick = { onSortChange(AppSortOrder.INSTALL_DATE); showSortMenu = false },
                    leadingIcon = if (sortOrder == AppSortOrder.INSTALL_DATE) {
                        { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filterAllApps)) },
                    onClick = { onFilterChange(false); showSortMenu = false },
                    leadingIcon = if (!filterNoIcon) {
                        { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filterWithoutIcon)) },
                    onClick = { onFilterChange(true); showSortMenu = false },
                    leadingIcon = if (filterNoIcon) {
                        { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null
                )
            }
        }
    }
    }
}