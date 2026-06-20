@file:OptIn(ExperimentalLayoutApi::class)

package dev.alembiconsProject.alembicons.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.alembiconsProject.alembicons.BuildConfig
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.alembiconsProject.alembicons.MainViewModel
import dev.alembiconsProject.alembicons.WatchViewModel
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.watch.RuleWithDetails
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import kotlinx.coroutines.flow.first

@Composable
fun WatchScreen(onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val viewModel: MainViewModel = viewModel()
    val watchViewModel: WatchViewModel = hiltViewModel()

    val rules by watchViewModel.rules.collectAsState()
    val apps = viewModel.appProvider.applicationList
    val packs = viewModel.appProvider.iconPacks

    // null = list; otherwise the editor is open (editing this rule, or a new rule when blank)
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RuleWithDetails?>(null) }
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteAllCompleted by remember { mutableStateOf(false) }
    var applySuggestionId by remember { mutableStateOf<Long?>(null) }

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
                // Back from the editor returns to the rule list rather than closing
                // the whole watch screen
                BackHandler(enabled = showEditor) {
                    showEditor = false
                    editing = null
                }

                // Slide the editor in from the right like a forward navigation,
                // and back to the list from the left, instead of a hard swap
                AnimatedContent(
                    targetState = showEditor,
                    transitionSpec = {
                        if (targetState) {
                            (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn()) togetherWith
                                (slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { -it / 5 } + fadeOut())
                        } else {
                            (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { -it / 5 } + fadeIn()) togetherWith
                                (slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeOut())
                        }
                    },
                    label = "watchEditor"
                ) { editorOpen ->
                if (editorOpen) {
                    WatchRuleEditor(
                        existing = editing,
                        apps = apps,
                        packs = packs,
                        onClose = { showEditor = false; editing = null },
                        onSave = { selApps, watchAll, selPacks ->
                            watchViewModel.saveRule(editing, selApps, watchAll, selPacks)
                            showEditor = false
                            editing = null
                        }
                    )
                } else {
                    WatchRuleList(
                        rules = rules,
                        apps = apps,
                        packs = packs,
                        isRefreshing = watchViewModel.isChecking,
                        onRefresh = {
                            watchViewModel.runCheck { found ->
                                val msg = if (found == 0) {
                                    context.getString(R.string.watchRefreshNone)
                                } else {
                                    context.getString(R.string.watchRefreshFound, found)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClose = onDismiss,
                        onAdd = { editing = null; showEditor = true },
                        onEdit = { editing = it; showEditor = true },
                        onApply = { rule -> rule.suggestions.firstOrNull()?.id?.let { applySuggestionId = it } },
                        onDelete = { ruleId -> pendingDelete = ruleId },
                        onDeleteAllCompleted = { pendingDeleteAllCompleted = true },
                        onSimulate = { watchViewModel.simulate() }
                    )
                }
                }
            }
        }
    }

    pendingDelete?.let { ruleId ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.deleteRuleTitle),
            text = stringResource(R.string.deleteRuleText),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                watchViewModel.deleteRule(ruleId)
            }
        )
    }

    if (pendingDeleteAllCompleted) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.deleteAllCompletedTitle),
            text = stringResource(R.string.deleteAllCompletedText),
            onDismiss = { pendingDeleteAllCompleted = false },
            onConfirm = {
                pendingDeleteAllCompleted = false
                watchViewModel.deleteCompleted()
            }
        )
    }

    // Tapping a completed rule opens the same apply modal right here in the watch screen
    applySuggestionId?.let { sid ->
        WatchApplyModal(sid) { applySuggestionId = null }
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
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
    onApply: (RuleWithDetails) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteAllCompleted: () -> Unit,
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
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionLabel(stringResource(R.string.watchCompleted), Modifier.weight(1f))
                                IconButton(onClick = onDeleteAllCompleted, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        stringResource(R.string.deleteAllCompleted),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        items(completed, key = { it.rule.id }) { rule ->
                            Box(Modifier.animateItem()) {
                                CompletedRuleCard(
                                    rule, apps, packs,
                                    onClick = { onApply(rule) },
                                    onDelete = { onDelete(rule.rule.id) }
                                )
                            }
                        }
                    }
                    if (active.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.watchActive)) }
                        items(active, key = { it.rule.id }) { rule ->
                            Box(Modifier.animateItem()) {
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
        }

        val view = LocalView.current
        FloatingActionButton(
            onClick = { view.performTapHaptic(); onAdd() },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, stringResource(R.string.addWatchRule))
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = modifier.padding(start = 4.dp, top = 4.dp)
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
            // Apps and pack tags span the full width; the actions sit at the bottom.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.watching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(end = 4.dp)
                )
                if (rule.rule.watchAllPacks) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
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
                    rule.packs.forEach { rp ->
                        val pack = packs.find { it.packageName == rp.iconPackPackage }
                        PackLabel(rp.iconPackPackage, pack?.applicationName)
                    }
                }
            }

            // Actions at the bottom, right-aligned: delete sits to the left of the edit
            // pill so the apps and pack tags above can use the card's full width.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Delete, stringResource(R.string.deleteRule),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                FilledTonalButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.edit))
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
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.newIconIn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(end = 4.dp)
                )
                rule.packs.forEach { rp ->
                    val pack = packs.find { it.packageName == rp.iconPackPackage }
                    PackLabel(rp.iconPackPackage, pack?.applicationName)
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

