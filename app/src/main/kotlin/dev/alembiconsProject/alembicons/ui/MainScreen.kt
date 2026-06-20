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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.AppFilterNoIconKey
import dev.alembiconsProject.alembicons.data.AppSortOrderKey
import dev.alembiconsProject.alembicons.data.BackgroundColorKey
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getColorValue
import dev.alembiconsProject.alembicons.data.getDefaultBackgroundColor
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.getPreferencesValue
import dev.alembiconsProject.alembicons.data.setBooleanValue
import dev.alembiconsProject.alembicons.data.setEnumValue
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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
    var isInRefresh by rememberSaveable { mutableStateOf(false) }
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
        topBar = { TitleBar(scrollBehavior) { isInRefresh = it } },
        floatingActionButton = { BuildPackFab(isInRefresh, expanded = listState.isScrollingUp()) },
        bottomBar = { BottomBar() }
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
    val pendingSuggestion = activity.pendingWatchSuggestionId
    if (pendingSuggestion != null) {
        WatchApplyModal(pendingSuggestion) { activity.clearPendingWatchSuggestion() }
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
    val activity = getCurrentMainActivity()
    val pm = LocalContext.current.packageManager
    val applications = activity.appProvider.applicationList

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

    // Keep the original index — ApplicationItem edits appProvider.applicationList by position
    val displayList = when (sortOrder) {
        AppSortOrder.NAME -> applications.withIndex().toList()
        AppSortOrder.INSTALL_DATE -> applications.withIndex()
            .sortedByDescending { installTimes[it.value.packageName] ?: 0L }
    }.let { list ->
        if (filterNoIcon) list.filter { it.value.createdIcon == null } else list
    }.let { list ->
        // Filter before the LazyColumn so non-matching rows don't become empty items
        if (filter.isEmpty()) list else list.filter { it.value.appName.contains(filter, true) }
    }

    // Keyed items make LazyColumn follow the previously visible app to its new
    // position when the order changes — jump back to the top instead
    LaunchedEffect(sortOrder, filterNoIcon) {
        listState.scrollToItem(0)
    }

    // The app list is loaded off the main thread at startup; show a spinner until
    // it arrives instead of a blank screen that looks frozen
    if (!activity.appProvider.applicationsLoaded && applications.isEmpty()) {
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
    val activity = getCurrentMainActivity()
    val dynamicColor = themed && supportDynamicColors()
    val view = LocalView.current

    var openAppOptions by rememberSaveable { mutableStateOf(false) }
    var openWarning by rememberSaveable { mutableStateOf(false) }

    Surface(
        onClick = {
            view.performTapHaptic()
            if (activity.appProvider.iconPackLoaded) {
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
            // Converting the drawable is not free — cache it per icon
            val bitmap = remember(app.icon) { app.icon.toSafeBitmapOrNull() }
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
    val activity = getCurrentMainActivity()

    AppOptions(iconPacks, app, themed, { icon, scale ->
        activity.lifecycleScope.launch(Dispatchers.Default) {
            activity.appProvider.editApplication(index, app.changeExport(icon, scale))
            activity.markIconChanged(app.packageName, app.activityName)
            onDismiss()
        }
    }, {
        onDismiss()
    }) {
        onDismiss()
        activity.appProvider.editApplication(index, app.changeExport(null, 1f))
    }
}

@Composable
fun RefreshButton(onChangeIsRefresh: (Boolean) -> Unit) {
    val preferences = getPreferences().getPreferencesValue()
    val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)

    val activity = getCurrentMainActivity()

    var openWarning by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = {
        activity.lifecycleScope.launch(Dispatchers.Default) {
            if (!activity.appProvider.iconPackLoaded && iconPackageName != "") {
                openWarning = true
                return@launch
            }
            onChangeIsRefresh(true)

            activity.appProvider.refreshIcons(preferences)

            onChangeIsRefresh(false)
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildPackFab(isInRefresh: Boolean, expanded: Boolean = true) {
    val activity = getCurrentMainActivity()
    val preferences = getPreferences().getPreferencesValue()
    val view = LocalView.current

    var openBuilder by rememberSaveable { mutableStateOf(false) }
    var openSuccess by remember { mutableStateOf(false) }
    var openInRefresh by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    ExtendedFloatingActionButton(
        onClick = {
            if (isInRefresh) {
                openInRefresh = true
                return@ExtendedFloatingActionButton
            }

            // Review the whole pack before committing to a build
            view.performTapHaptic()
            showPreview = true
        },
        icon = {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null
            )
        },
        text = { Text(stringResource(id = R.string.buildIconPack)) },
        expanded = expanded,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )

    if (showPreview) {
        BuildPackPreview(
            onDismiss = { showPreview = false },
            onBuild = {
                showPreview = false
                view.performConfirmHaptic()
                text = ""
                openBuilder = true
                activity.lifecycleScope.launch(Dispatchers.Default) {
                    val iconPack = activity.appProvider.buildAndSignIconPack(preferences) {
                        text = it
                    }

                    openBuilder = false
                    openSuccess = activity.appProvider.installIconPack(iconPack)
                }
            }
        )
    }

    if (openBuilder) {
        AlertDialog(
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            onDismissRequest = {},
            icon = {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            },
            title = { Text(stringResource(id = R.string.iconPack)) },
            text = {
                // Show only the current step, crossfading between them, instead of an
                // ever-growing log
                Crossfade(targetState = text, label = "buildStep") { step ->
                    Text(
                        text = step,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { }
        )
    }

    if (openSuccess) {
        ShowToast(stringResource(id = R.string.iconPackInstalled))
        openSuccess = false
    }

    if (openInRefresh) {
        ShowToast(stringResource(id = R.string.iconsStillGenerated))
        openInRefresh = false
    }
}

/**
 * Full-screen review of every icon that will go into the pack (apps that have a
 * created icon), shown before the actual build so the user can judge the set as a
 * whole. The Build button kicks off the real build.
 */
@Composable
fun BuildPackPreview(onDismiss: () -> Unit, onBuild: () -> Unit) {
    val activity = getCurrentMainActivity()
    val changed = activity.recentlyChangedIcons.toSet()
    // Icons changed this session float to the top so the user sees them without
    // scrolling; the rest stay alphabetical
    val themedApps = activity.appProvider.applicationList
        .filter { it.createdIcon != null }
        .sortedWith(
            compareByDescending<PackageInfoStruct> { "${it.packageName}/${it.activityName}" in changed }
                .thenBy { it.appName.lowercase() }
        )

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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = stringResource(R.string.buildPreviewTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.buildPreviewCount, themedApps.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (themedApps.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.buildPreviewEmpty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(72.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(themedApps, key = { "${it.packageName}/${it.activityName}" }) { app ->
                            BuildPreviewItem(
                                app = app,
                                changed = "${app.packageName}/${app.activityName}" in changed
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Button(
                    onClick = onBuild,
                    enabled = themedApps.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.buildIconPack))
                }
            }
        }
    }
}

@Composable
private fun BuildPreviewItem(app: PackageInfoStruct, changed: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            app.createdIcon?.let { icon ->
                Image(
                    painter = icon.getPainter(),
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
            // Green dot marks icons changed this session so they're easy to spot
            if (changed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TitleBar(
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onRefreshChange: (Boolean) -> Unit = {}
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
            RefreshButton {
                onRefreshChange(it)
            }
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
fun InfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(text = stringResource(id = R.string.refreshIconDescription))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "Build",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(text = stringResource(id = R.string.buildIconDescription))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = "Watch",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(text = stringResource(id = R.string.watchIconDescription))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomBar() {
    if (!getCurrentMainActivity().appProvider.iconPackLoaded) {
        BottomAppBar(containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(id = R.string.syncIconPack), Modifier.padding(4.dp))
                LoadingIndicator(
                    modifier = Modifier
                        .width(40.dp)
                        .padding(4.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
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