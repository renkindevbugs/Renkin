@file:OptIn(ExperimentalMaterial3Api::class)

package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.watch.AppComponent
import dev.alembiconsProject.alembicons.data.watch.RuleWithDetails
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchRuleEditor(
    existing: RuleWithDetails?,
    apps: List<PackageInfoStruct>,
    packs: List<IconPack>,
    onClose: () -> Unit,
    onSave: (apps: List<AppComponent>, watchAll: Boolean, packs: List<String>) -> Unit
) {
    val selectedApps: SnapshotStateList<AppComponent> = remember {
        existing?.apps?.map { AppComponent(it.packageName, it.activityName) }.orEmpty().toMutableStateList()
    }
    val selectedPacks: SnapshotStateList<String> = remember {
        existing?.packs?.map { it.iconPackPackage }.orEmpty().toMutableStateList()
    }
    var watchAll by remember { mutableStateOf(existing?.rule?.watchAllPacks ?: false) }
    var query by remember { mutableStateOf("") }

    var sortOrder by remember { mutableStateOf(AppSortOrder.NAME) }
    var filterNoIcon by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val context = getCurrentContext()
    // Reading first-install time hits PackageManager once per app; doing it for every
    // app on the main thread was what made the editor take ~1s to open. Compute it off
    // the main thread instead — until it arrives, INSTALL_DATE sort just shows the
    // default order. (Times don't change at runtime, so this runs once per app set.)
    val installTimes by produceState(emptyMap<String, Long>(), apps) {
        value = withContext(Dispatchers.IO) {
            apps.associate { app ->
                app.packageName to runCatching {
                    context.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
                }.getOrDefault(0L)
            }
        }
    }

    val sortedPacks = remember(packs) { packs.sortedBy { it.applicationName.lowercase() } }
    val filteredApps = remember(apps, query, sortOrder, filterNoIcon, installTimes) {
        var seq = apps.asSequence()
        if (query.isNotBlank()) seq = seq.filter { it.appName.contains(query.trim(), ignoreCase = true) }
        if (filterNoIcon) seq = seq.filter { it.createdIcon == null }
        when (sortOrder) {
            AppSortOrder.NAME -> seq.sortedBy { it.appName.lowercase() }
            AppSortOrder.INSTALL_DATE -> seq.sortedByDescending { installTimes[it.packageName] ?: 0L }
        }.toList()
    }
    // 3 rows × 3 columns per page → a horizontally paged grid with dots
    val appPages = filteredApps.chunked(9)
    val pagerState = rememberPagerState(pageCount = { appPages.size.coerceAtLeast(1) })
    // Shrink the grid to the rows actually needed (e.g. a narrow search result),
    // but keep full 3-row height once it pages so swiping doesn't resize it
    val visibleRows = if (appPages.size <= 1) {
        (((appPages.firstOrNull()?.size ?: 0) + 2) / 3).coerceIn(1, 3)
    } else 3
    val gridHeight = (visibleRows * 112 + (visibleRows - 1) * 8).dp
    LaunchedEffect(query, sortOrder, filterNoIcon) {
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    val canSave = selectedApps.isNotEmpty() && (watchAll || selectedPacks.isNotEmpty())

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
            }
            Text(
                text = stringResource(if (existing == null) R.string.newWatchRule else R.string.editWatchRule),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    onSave(selectedApps.toList(), watchAll, selectedPacks.toList())
                },
                enabled = canSave,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.appsToWatch),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(stringResource(R.string.searchApps)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sortApps), tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sortByName)) },
                            onClick = { sortOrder = AppSortOrder.NAME; showSortMenu = false },
                            leadingIcon = if (sortOrder == AppSortOrder.NAME) {
                                { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sortByInstallDate)) },
                            onClick = { sortOrder = AppSortOrder.INSTALL_DATE; showSortMenu = false },
                            leadingIcon = if (sortOrder == AppSortOrder.INSTALL_DATE) {
                                { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filterAllApps)) },
                            onClick = { filterNoIcon = false; showSortMenu = false },
                            leadingIcon = if (!filterNoIcon) {
                                { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filterWithoutIcon)) },
                            onClick = { filterNoIcon = true; showSortMenu = false },
                            leadingIcon = if (filterNoIcon) {
                                { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(gridHeight)
            ) { page ->
                TileRows(appPages.getOrNull(page).orEmpty()) { app ->
                    val comp = AppComponent(app.packageName, app.activityName)
                    val selected = selectedApps.any { it.packageName == comp.packageName && it.activityName == comp.activityName }
                    IconTile(
                        bitmap = rememberAppBitmap(app),
                        label = clipLabel(app.appName, 13),
                        selected = selected,
                        overlayIcon = app.createdIcon
                    ) {
                        if (selected) selectedApps.removeAll { it.packageName == comp.packageName && it.activityName == comp.activityName }
                        else selectedApps.add(comp)
                    }
                }
            }

            if (appPages.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(appPages.size) { i ->
                        val sel = pagerState.currentPage == i
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (sel) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }

            // Selected apps sit below the grid so adding one doesn't shove the grid down;
            // single scrolling row, labels clipped to keep chips small
            if (selectedApps.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedApps.toList().forEach { comp ->
                        val app = apps.find { it.packageName == comp.packageName && it.activityName == comp.activityName }
                        RemovableChip(
                            label = clipLabel(app?.appName ?: comp.packageName, 7),
                            iconApp = app,
                            iconPackPackage = null,
                            onRemove = { selectedApps.remove(comp) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.watchAllPacksLabel),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = watchAll, onCheckedChange = { watchAll = it })
            }

            if (!watchAll) {
                Text(
                    text = stringResource(R.string.iconPacksToCheck),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    TileRows(sortedPacks) { pack ->
                        val selected = selectedPacks.contains(pack.packageName)
                        IconTile(rememberPackBitmap(pack.packageName), clipLabel(pack.applicationName, 13), selected) {
                            if (selected) selectedPacks.remove(pack.packageName)
                            else selectedPacks.add(pack.packageName)
                        }
                    }
                }
                if (selectedPacks.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedPacks.toList().forEach { pkg ->
                            val pack = packs.find { it.packageName == pkg }
                            RemovableChip(
                                label = clipLabel(pack?.applicationName ?: pkg, 7),
                                iconApp = null,
                                iconPackPackage = pkg,
                                onRemove = { selectedPacks.remove(pkg) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Clips a label to [max] characters with an ellipsis so it stays on one line. */
private fun clipLabel(name: String, max: Int): String =
    if (name.length > max) name.take(max) + "…" else name

/**
 * One shared selectable tile (rounded card: icon on top, single-line name below) for
 * apps and packs. [overlayIcon], when set (apps that already have a chosen icon),
 * is shown as a small badge in the big icon's bottom-right corner.
 */
@Composable
private fun IconTile(
    bitmap: ImageBitmap?,
    label: String,
    selected: Boolean,
    overlayIcon: IconPackDrawable? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp, start = 4.dp, end = 4.dp)
        ) {
            Box(Modifier.size(54.dp)) {
                if (bitmap != null) {
                    Image(
                        painter = BitmapPainter(bitmap),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {}
                }
                if (overlayIcon != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Image(
                            painter = overlayIcon.getPainter(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp)
                        )
                    }
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
            )
        }
    }
}

/** Lays [items] into rows of [columns], padding the last row so tile widths stay equal. */
@Composable
private fun <T> TileRows(items: List<T>, columns: Int = 3, tile: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) { tile(item) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RemovableChip(
    label: String,
    iconApp: PackageInfoStruct?,
    iconPackPackage: String?,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconApp != null) AppIcon(iconApp, iconApp.packageName, 18.dp)
            else if (iconPackPackage != null) PackIconImage(iconPackPackage, 18.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp).padding(start = 2.dp)) {
                Icon(
                    Icons.Filled.Close, stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
