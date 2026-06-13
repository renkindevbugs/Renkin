package dev.alembiconsProject.alembicons.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class AppSortOrder { NAME, INSTALL_DATE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainColumn(iconPacks: List<IconPack>) {
    var packageFilter by remember { mutableStateOf("") }
    var isInRefresh by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val prefs = getPreferences()
    val scope = rememberCoroutineScope()
    val sortOrder = prefs.getEnumValue(AppSortOrderKey, AppSortOrder.NAME)
    val filterNoIcon = prefs.getBooleanValue(AppFilterNoIconKey)

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
        floatingActionButton = { BuildPackFab(isInRefresh) },
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
            ApplicationList(iconPacks, packageFilter, sortOrder, filterNoIcon)
        }
    }
}

@Composable
fun ApplicationList(
    iconPacks: List<IconPack>,
    filter: String,
    sortOrder: AppSortOrder,
    filterNoIcon: Boolean
) {
    val activity = getCurrentMainActivity()
    val pm = LocalContext.current.packageManager
    val applications = activity.appProvider.applicationList
    val listState = rememberLazyListState()

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
            ApplicationItem(iconPacks, indexedApp.value, indexedApp.index, themed, bgColorValue)
        }
    }
}

@Composable
fun ApplicationItem(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    index: Int,
    themed: Boolean,
    bgColorValue: Color
) {
    val activity = getCurrentMainActivity()
    val dynamicColor = themed && supportDynamicColors()

    var openAppOptions by rememberSaveable { mutableStateOf(false) }
    var openWarning by rememberSaveable { mutableStateOf(false) }

    Surface(
        onClick = {
            if (activity.appProvider.iconPackLoaded) {
                openAppOptions = true
            } else {
                openWarning = true
            }
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
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

            if (app.createdIcon != null) {
                Image(
                    painter = app.createdIcon.getPainter(),
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

    AppOptions(iconPacks, app, themed, { icon ->
        CoroutineScope(Dispatchers.Default).launch {
            activity.appProvider.editApplication(index, app.changeExport(icon))
            onDismiss()
        }
    }, {
        onDismiss()
    }) {
        onDismiss()
        activity.appProvider.editApplication(index, app.changeExport(null))
    }
}

@Composable
fun RefreshButton(onChangeIsRefresh: (Boolean) -> Unit) {
    val preferences = getPreferences().getPreferencesValue()
    val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)

    val activity = getCurrentMainActivity()

    var openWarning by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = {
        CoroutineScope(Dispatchers.Default).launch {
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

@Composable
fun BuildPackFab(isInRefresh: Boolean) {
    val activity = getCurrentMainActivity()
    val preferences = getPreferences().getPreferencesValue()

    var openBuilder by rememberSaveable { mutableStateOf(false) }
    var openSuccess by remember { mutableStateOf(false) }
    var openInRefresh by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    ExtendedFloatingActionButton(
        onClick = {
            if (isInRefresh) {
                openInRefresh = true
                return@ExtendedFloatingActionButton
            }

            text = ""
            openBuilder = true
            CoroutineScope(Dispatchers.Default).launch {
                val iconPack = activity.appProvider.buildAndSignIconPack(preferences) {
                    text += it + "\n"
                }

                openBuilder = false
                openSuccess = activity.appProvider.installIconPack(iconPack)
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null
            )
        },
        text = { Text(stringResource(id = R.string.buildIconPack)) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )

    if (openBuilder) {
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = {},
            title = { Text(stringResource(id = R.string.iconPack)) },
            text = {
                Text(text = text)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TitleBar(
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onRefreshChange: (Boolean) -> Unit = {}
) {
    val prefs = getPreferences()
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

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