@file:OptIn(ExperimentalLayoutApi::class)

package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.watch.AppComponent
import dev.alembiconsProject.alembicons.data.watch.RuleWithDetails
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import kotlinx.coroutines.launch

@Composable
fun WatchScreen(onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    val repo = remember { WatchRepository(context) }
    val scope = rememberCoroutineScope()

    val rules by repo.rules.collectAsState(initial = emptyList())
    val apps = activity.appProvider.applicationList
    val packs = activity.appProvider.iconPacks

    // null = list; otherwise the editor is open (editing this rule, or a new rule when blank)
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RuleWithDetails?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                if (showEditor) {
                    WatchRuleEditor(
                        existing = editing,
                        apps = apps,
                        packs = packs,
                        onClose = { showEditor = false; editing = null },
                        onSave = { selApps, watchAll, selPacks ->
                            scope.launch {
                                val target = editing
                                if (target == null) {
                                    repo.createRule(selApps, watchAll, selPacks)
                                } else {
                                    repo.updateRule(target.rule.id, selApps, watchAll, selPacks)
                                }
                            }
                            showEditor = false
                            editing = null
                        }
                    )
                } else {
                    WatchRuleList(
                        rules = rules,
                        apps = apps,
                        packs = packs,
                        onClose = onDismiss,
                        onAdd = { editing = null; showEditor = true },
                        onEdit = { editing = it; showEditor = true },
                        onDelete = { ruleId -> scope.launch { repo.deleteRule(ruleId) } }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rule list (Completed on top so it's the first thing the user can clear, then Active)
// ---------------------------------------------------------------------------

@Composable
private fun WatchRuleList(
    rules: List<RuleWithDetails>,
    apps: List<PackageInfoStruct>,
    packs: List<IconPack>,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (RuleWithDetails) -> Unit,
    onDelete: (Long) -> Unit
) {
    val completed = rules.filter { it.rule.completed }
    val active = rules.filter { !it.rule.completed }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
                Text(
                    text = stringResource(R.string.watchedIcons),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.noWatchRulesYet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (completed.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.watchCompleted)) }
                        items(completed, key = { it.rule.id }) { rule ->
                            CompletedRuleCard(rule, apps, packs, onDelete = { onDelete(rule.rule.id) })
                        }
                    }
                    if (active.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.watchActive)) }
                        items(active, key = { it.rule.id }) { rule ->
                            ActiveRuleCard(
                                rule, apps, packs,
                                onEdit = { onEdit(rule) },
                                onDelete = { onDelete(rule.rule.id) }
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, stringResource(R.string.addWatchRule))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ActiveRuleCard(
    rule: RuleWithDetails,
    apps: List<PackageInfoStruct>,
    packs: List<IconPack>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rule.apps.forEach { ra ->
                    val app = apps.find { it.packageName == ra.packageName && it.activityName == ra.activityName }
                    AppPill(app, ra.packageName)
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.watching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                if (rule.rule.watchAllPacks) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Filled.Layers, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.allIconPacks),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        rule.packs.forEach { rp ->
                            val pack = packs.find { it.packageName == rp.iconPackPackage }
                            PackLabel(rp.iconPackPackage, pack?.applicationName)
                        }
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Edit, stringResource(R.string.editWatchRule),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete, stringResource(R.string.deleteRule),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletedRuleCard(
    rule: RuleWithDetails,
    apps: List<PackageInfoStruct>,
    packs: List<IconPack>,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ra = rule.apps.firstOrNull()
                val app = ra?.let { apps.find { a -> a.packageName == it.packageName && a.activityName == it.activityName } }
                AppIcon(app, ra?.packageName ?: "", 28.dp)
                Text(
                    text = app?.appName ?: (ra?.packageName ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f, fill = false)
                )
                DoneBadge()
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).padding(start = 4.dp)) {
                    Icon(
                        Icons.Filled.Delete, stringResource(R.string.deleteRule),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.newIconIn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rule.packs.forEach { rp ->
                        val pack = packs.find { it.packageName == rp.iconPackPackage }
                        PackLabel(rp.iconPackPackage, pack?.applicationName)
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Check, null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.watchDone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rule editor
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchRuleEditor(
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

    val sortedPacks = remember(packs) { packs.sortedBy { it.applicationName.lowercase() } }
    val filteredApps = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.appName.contains(query.trim(), ignoreCase = true) }
    }
    val canSave = selectedApps.isNotEmpty() && (watchAll || selectedPacks.isNotEmpty())

    Column(Modifier.fillMaxSize()) {
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
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.appsToWatch),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (selectedApps.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedApps.toList().forEach { comp ->
                        val app = apps.find { it.packageName == comp.packageName && it.activityName == comp.activityName }
                        RemovableChip(
                            label = app?.appName ?: comp.packageName,
                            iconApp = app,
                            iconPackPackage = null,
                            onRemove = { selectedApps.remove(comp) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(stringResource(R.string.searchApps)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            // Horizontally scrolling 3-row grid keeps the picker compact vertically
            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(252.dp)
                    .padding(top = 10.dp)
            ) {
                items(filteredApps, key = { "${it.packageName}/${it.activityName}" }) { app ->
                    val comp = AppComponent(app.packageName, app.activityName)
                    val selected = selectedApps.any { it.packageName == comp.packageName && it.activityName == comp.activityName }
                    AppTile(app, selected) {
                        if (selected) selectedApps.removeAll { it.packageName == comp.packageName && it.activityName == comp.activityName }
                        else selectedApps.add(comp)
                    }
                }
            }
            Text(
                text = stringResource(R.string.swipeForMore),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 2.dp, start = 2.dp)
            )

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
                if (selectedPacks.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedPacks.toList().forEach { pkg ->
                            val pack = packs.find { it.packageName == pkg }
                            RemovableChip(
                                label = pack?.applicationName ?: pkg,
                                iconApp = null,
                                iconPackPackage = pkg,
                                onRemove = { selectedPacks.remove(pkg) }
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedPacks, key = { it.packageName }) { pack ->
                        val selected = selectedPacks.contains(pack.packageName)
                        PackRow(pack, selected) {
                            if (selected) selectedPacks.remove(pack.packageName)
                            else selectedPacks.add(pack.packageName)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small reusable pieces
// ---------------------------------------------------------------------------

@Composable
private fun AppTile(app: PackageInfoStruct, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(86.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        AppIcon(app, app.packageName, 44.dp)
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun PackRow(pack: IconPack, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PackIconImage(pack.packageName, 26.dp)
            Text(
                text = pack.applicationName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
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

/** App pill (icon + name) used on the active rule card. */
@Composable
private fun AppPill(app: PackageInfoStruct?, fallbackPackage: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app, fallbackPackage, 18.dp)
            Text(
                text = app?.appName ?: fallbackPackage,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/** Small pack icon + name used in the "Watching" / "New icon in" rows. */
@Composable
private fun PackLabel(packPackage: String, name: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PackIconImage(packPackage, 16.dp)
        Text(
            text = name ?: packPackage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

@Composable
private fun AppIcon(app: PackageInfoStruct?, fallbackPackage: String, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(app?.packageName ?: fallbackPackage, app?.internalVersion) {
        app?.icon?.toSafeBitmapOrNull()
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = RoundedCornerShape(size / 4),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {}
    }
}

@Composable
private fun PackIconImage(packPackage: String, size: androidx.compose.ui.unit.Dp) {
    val context = getCurrentContext()
    val bitmap = remember(packPackage) {
        try {
            context.packageManager.getApplicationIcon(packPackage).toSafeBitmapOrNull()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {}
    }
}
