@file:OptIn(ExperimentalLayoutApi::class)

package dev.alembiconsProject.alembicons.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.alembiconsProject.alembicons.BuildConfig
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.watch.AppComponent
import dev.alembiconsProject.alembicons.data.watch.IconSuggestion
import dev.alembiconsProject.alembicons.data.watch.IconSuggestionCandidate
import dev.alembiconsProject.alembicons.data.watch.RuleWithDetails
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.service.WatchChecker
import dev.alembiconsProject.alembicons.service.WatchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

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
                            // Capture the edit target now — the coroutine runs after we reset state below
                            val target = editing
                            scope.launch {
                                val ruleId = if (target == null) {
                                    repo.createRule(selApps, watchAll, selPacks)
                                } else {
                                    repo.updateRule(target.rule.id, selApps, watchAll, selPacks)
                                    target.rule.id
                                }
                                // Snapshot current icons so a later pack update is the trigger,
                                // not the icons that already existed when the rule was made
                                WatchChecker(context).baselineRule(ruleId)
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
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                val fired = WatchChecker(context).runCheck()
                                isRefreshing = false
                                val msg = if (fired.isEmpty()) {
                                    context.getString(R.string.watchRefreshNone)
                                } else {
                                    context.getString(R.string.watchRefreshFound, fired.size)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClose = onDismiss,
                        onAdd = { editing = null; showEditor = true },
                        onEdit = { editing = it; showEditor = true },
                        onDelete = { ruleId -> pendingDelete = ruleId },
                        onSimulate = {
                            scope.launch {
                                // Establish a baseline, stale it, then re-check via the worker
                                // so the full notify + deep-link path runs (debug builds only)
                                WatchChecker(context).runCheck()
                                repo.debugStaleAllStates()
                                WatchWorker.runNow(context)
                            }
                        }
                    )
                }
            }
        }
    }

    pendingDelete?.let { ruleId ->
        DeleteRuleDialog(
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch { repo.deleteRule(ruleId) }
            }
        )
    }
}

/**
 * Shown on the home screen when opened from an icon-watch notification (phase 6):
 * the app's current icon vs the newly found one (with a pack picker if several packs
 * offer one). Confirm stores the icon and toasts to press Build; cancel keeps the
 * suggestion so it can still be applied later from the watch screen.
 */
@Composable
fun WatchApplyModal(suggestionId: Long, onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    val repo = remember { WatchRepository(context) }
    val prefs = getPreferences()
    val scope = rememberCoroutineScope()

    var suggestion by remember(suggestionId) { mutableStateOf<IconSuggestion?>(null) }
    var candidates by remember(suggestionId) { mutableStateOf<List<IconSuggestionCandidate>>(emptyList()) }
    var selectedPack by remember(suggestionId) { mutableStateOf<String?>(null) }
    var newIcon by remember(suggestionId) { mutableStateOf<IconPackDrawable?>(null) }
    var loaded by remember(suggestionId) { mutableStateOf(false) }

    LaunchedEffect(suggestionId) {
        val s = repo.getSuggestion(suggestionId)
        val c = repo.getCandidates(suggestionId)
        suggestion = s
        candidates = c
        selectedPack = c.firstOrNull()?.iconPackPackage
        loaded = true
        if (s == null) onDismiss() // already handled/deleted → nothing to show
    }

    val apps = activity.appProvider.applicationList
    val app = suggestion?.let { s -> apps.find { it.packageName == s.packageName && it.activityName == s.activityName } }

    // (Re)generate the new icon for the selected pack
    LaunchedEffect(selectedPack, suggestion, app) {
        val s = suggestion ?: return@LaunchedEffect
        val pack = selectedPack ?: return@LaunchedEffect
        val targetApp = app ?: return@LaunchedEffect
        val candidate = candidates.find { it.iconPackPackage == pack } ?: return@LaunchedEffect
        newIcon = null
        newIcon = withContext(Dispatchers.Default) {
            val options = GenerationOptions.fromPreferences(prefs.data.first(), context, override = true)
            activity.appProvider.getIconFromPackDrawable(targetApp, pack, candidate.drawableName, options)
        }
    }

    if (!loaded || suggestion == null) return

    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watchApplyTitle)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = app?.appName ?: suggestion!!.packageName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ComparePreview(app?.let { rememberAppBitmap(it) }, null)
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ComparePreview(null, newIcon)
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.watchApplyFromPack),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    candidates.forEach { candidate ->
                        val pack = packsName(activity.appProvider.iconPacks, candidate.iconPackPackage)
                        FilterChip(
                            selected = selectedPack == candidate.iconPackPackage,
                            onClick = { selectedPack = candidate.iconPackPackage },
                            label = { Text(pack, maxLines = 1) },
                            leadingIcon = { PackIconImage(candidate.iconPackPackage, 18.dp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
                val applyToast = stringResource(R.string.watchApplyToast)
                FilledIconButton(
                    onClick = {
                        val icon = newIcon
                        val targetApp = app
                        if (icon != null && targetApp != null) {
                            val index = apps.indexOfFirst {
                                it.packageName == targetApp.packageName && it.activityName == targetApp.activityName
                            }
                            if (index >= 0) {
                                activity.appProvider.editApplication(index, targetApp.changeExport(icon))
                            }
                            scope.launch { repo.deleteSuggestion(suggestionId) }
                            Toast.makeText(context, applyToast, Toast.LENGTH_LONG).show()
                        }
                        onDismiss()
                    },
                    enabled = newIcon != null && app != null
                ) {
                    Icon(Icons.Filled.Check, stringResource(R.string.confirm))
                }
            }
        }
    )
}

/** A small framed preview cell showing either a bitmap (current) or a drawable (new). */
@Composable
private fun ComparePreview(bitmap: androidx.compose.ui.graphics.ImageBitmap?, icon: IconPackDrawable?) {
    Surface(
        modifier = Modifier.size(72.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(BitmapPainter(bitmap), null, Modifier.fillMaxSize())
                icon != null -> Image(icon.getPainter(), null, Modifier.fillMaxSize())
                else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private fun packsName(packs: List<IconPack>, packageName: String): String =
    packs.find { it.packageName == packageName }?.applicationName ?: packageName

@Composable
private fun DeleteRuleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deleteRuleTitle)) },
        text = { Text(stringResource(R.string.deleteRuleText)) },
        confirmButton = {
            IconButton(onClick = onConfirm) {
                Icon(Icons.Filled.Check, stringResource(R.string.confirm), tint = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, stringResource(R.string.dismiss), tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// Rule list (Completed on top so it's the first thing the user can clear, then Active)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchRuleList(
    rules: List<RuleWithDetails>,
    apps: List<PackageInfoStruct>,
    packs: List<IconPack>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (RuleWithDetails) -> Unit,
    onDelete: (Long) -> Unit,
    onSimulate: () -> Unit
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
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (BuildConfig.DEBUG) {
                    IconButton(onClick = onSimulate) {
                        Icon(
                            Icons.Filled.BugReport,
                            stringResource(R.string.watchSimulate),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Pull down to run a manual check (the spinner stays until WatchChecker finishes)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (rules.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.noWatchRulesYet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(32.dp)
                                )
                            }
                        }
                    }
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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

    var sortOrder by remember { mutableStateOf(AppSortOrder.NAME) }
    var filterNoIcon by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val context = getCurrentContext()
    // First-install times don't change at runtime; recompute only when the set changes
    val installTimes = remember(apps.size) {
        apps.associate { app ->
            app.packageName to runCatching {
                context.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
            }.getOrDefault(0L)
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

// ---------------------------------------------------------------------------
// Small reusable pieces
// ---------------------------------------------------------------------------

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
private fun rememberAppBitmap(app: PackageInfoStruct): ImageBitmap? {
    return remember(app.packageName, app.internalVersion) {
        app.icon.toSafeBitmapOrNull()?.asImageBitmap()
    }
}

@Composable
private fun rememberPackBitmap(packPackage: String): ImageBitmap? {
    val context = getCurrentContext()
    return remember(packPackage) {
        try {
            context.packageManager.getApplicationIcon(packPackage).toSafeBitmapOrNull()?.asImageBitmap()
        } catch (_: Exception) {
            null
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

/** Pack pill (icon + name) used in the "Watching" / "New icon in" rows. */
@Composable
private fun PackLabel(packPackage: String, name: String?) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PackIconImage(packPackage, 18.dp)
            Text(
                text = name ?: packPackage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
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
