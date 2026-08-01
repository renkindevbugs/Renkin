@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.BuildConfig
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.WatchViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.ChangedOrange
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PermissionManager
import dev.renkinProject.renkin.packages.notificationSettingsIntent
import kotlinx.coroutines.flow.first

@Composable
fun WatchScreen(
    // Opened from an app's quick actions: that app's rule starts selected and scrolled to, so
    // "already watched" lands on the rule it means instead of the top of the list.
    highlightRuleId: Long? = null,
    onDismiss: () -> Unit
) {
    val context = getCurrentContext()
    val viewModel: MainViewModel = hiltViewModel()
    val watchViewModel: WatchViewModel = hiltViewModel()
    val toaster = LocalToaster.current

    val rules by watchViewModel.rules.collectAsState()
    val apps = viewModel.applicationList
    // Renkin's own generated packs can't be watched (the checker ignores them), so don't
    // offer them in the rule editor's pack picker either.
    val packs = viewModel.iconPacks.filterNot { IconPackBuilder.isOwnPack(it.packageName) }

    // Icon-watch is the only feature that posts notifications now, so ask for the permission
    // here (it used to be requested by the removed package-added setting).
    val activity = getCurrentMainActivity()
    // Below API 33 there is no runtime prompt, so a user who turned notifications off would get
    // a silent no-op — point them at the system screen instead.
    var showNotificationSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val permissions = PermissionManager(activity)
        if (permissions.isPostNotificationEnabled()) return@LaunchedEffect
        if (permissions.canAskForPostNotification()) permissions.askForPostNotification()
        else showNotificationSettings = true
    }

    if (showNotificationSettings) {
        RenkinAlertDialog(
            onDismissRequest = { showNotificationSettings = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.notificationsDisabledTitle)) },
            text = { Text(stringResource(R.string.notificationsDisabledText)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationSettings = false
                    // The settings app may be missing/locked down on odd ROMs.
                    runCatching { activity.startActivity(notificationSettingsIntent(activity)) }
                }) { Text(stringResource(R.string.settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationSettings = false }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }

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
                    if (!watchViewModel.isSavingRule) {
                        showEditor = false
                        editing = null
                    }
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
                        isSaving = watchViewModel.isSavingRule,
                        onClose = {
                            if (!watchViewModel.isSavingRule) {
                                showEditor = false
                                editing = null
                            }
                        },
                        onSave = { selApps, watchAll, selPacks ->
                            watchViewModel.saveRule(editing, selApps, watchAll, selPacks) {
                                showEditor = false
                                editing = null
                            }
                        }
                    )
                } else {
                    WatchRuleList(
                        rules = rules,
                        highlightRuleId = highlightRuleId,
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
                                toaster.show(msg)
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
        ConfirmDialog(
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
        ConfirmDialog(
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

// ---------------------------------------------------------------------------
// Rule list (Completed on top so it's the first thing the user can clear, then Active)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WatchRuleList(
    rules: List<RuleWithDetails>,
    highlightRuleId: Long? = null,
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

    // Single-selected active rule. Its Edit/Delete actions live in the bottom floating toolbar
    // instead of on every card; tapping an active card selects/deselects it. Completed rules are
    // never selectable (they keep their own apply/delete behaviour).
    var selectedId by remember { mutableStateOf(highlightRuleId) }
    val selectedRule = active.find { it.rule.id == selectedId }
    // Drop a stale selection if the rule was deleted or completed since it was picked.
    LaunchedEffect(active) {
        if (selectedId != null && active.none { it.rule.id == selectedId }) selectedId = null
    }
    // Back deselects before it would close the screen.
    BackHandler(enabled = selectedId != null) { selectedId = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Same top-bar chrome as Settings and Crash logs, so every fullscreen screen opens
            // with the identical back-arrow app bar. The dialog already applies the status-bar
            // inset, hence WindowInsets(0).
            TopAppBar(
                title = { Text(stringResource(R.string.watchedIcons)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                },
                actions = {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = onSimulate) {
                            Icon(
                                Icons.Filled.BugReport,
                                stringResource(R.string.watchSimulate),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
            HorizontalDivider()

            // Only meaningful while something is actively watched — hide it otherwise.
            if (active.isNotEmpty()) {
                WatchCheckScheduleRow()
            }

            // Pull down to run a manual check (the spinner stays until WatchChecker finishes)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Bottom padding = nav-bar inset (so buttons clear the system nav bar; the
                // dialog draws edge-to-edge with decorFitsSystemWindows = false) + room for
                // the FAB, so the last rule's Edit/Delete aren't hidden behind it.
                val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val rulesListState = rememberLazyListState()
                // Arriving from "already watched": bring that rule into view once the list has
                // rendered. Active rules sit below the completed section, so it is rarely on
                // screen already.
                LaunchedEffect(highlightRuleId, active) {
                    val target = highlightRuleId ?: return@LaunchedEffect
                    val position = active.indexOfFirst { it.rule.id == target }
                    if (position < 0) return@LaunchedEffect
                    // + the completed section's own header and cards, + the active header.
                    val before = if (completed.isEmpty()) 0 else completed.size + 1
                    rulesListState.animateScrollToItem(before + 1 + position)
                }
                LazyColumn(
                    state = rulesListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawVerticalScrollbar(rulesListState),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp + navBarInset
                    ),
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
                                    selected = rule.rule.id == selectedId,
                                    onClick = {
                                        selectedId = if (selectedId == rule.rule.id) null else rule.rule.id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom expandable floating toolbar. Collapsed (nothing selected) it's just the centered
        // "+" that adds a rule; selecting an active rule expands it — Edit on the left, Delete and
        // Deselect on the right — all acting on that rule.
        val view = LocalView.current
        HorizontalFloatingToolbar(
            expanded = selectedRule != null,
            leadingContent = {
                IconButton(onClick = { selectedRule?.let(onEdit) }) {
                    Icon(Icons.Filled.Edit, stringResource(R.string.edit))
                }
            },
            trailingContent = {
                IconButton(onClick = { selectedRule?.let { onDelete(it.rule.id) } }) {
                    Icon(
                        Icons.Filled.Delete,
                        stringResource(R.string.deleteRule),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            },
            content = {
                FilledIconButton(
                    onClick = { view.performTapHaptic(); selectedId = null; onAdd() },
                    modifier = Modifier.width(64.dp)
                ) {
                    Icon(Icons.Filled.Add, stringResource(R.string.addWatchRule))
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                .zIndex(1f)
        )
    }
}

/**
 * Shows when the next periodic check is due (from WorkManager's real schedule). In debug
 * builds it also exposes a frequency picker so the watcher can be tested without waiting
 * the full 24h — WorkManager's periodic floor is 15 min, so that's the smallest option.
 */
@Composable
private fun WatchCheckScheduleRow() {
    val watchViewModel: WatchViewModel = hiltViewModel()
    val context = getCurrentContext()
    val nextCheck by watchViewModel.nextCheckAt.collectAsState()
    val intervalMinutes by watchViewModel.checkIntervalMinutes.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val nextText = nextCheck?.let {
            stringResource(
                R.string.watchNextCheck,
                DateUtils.formatDateTime(
                    context, it,
                    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
                )
            )
        } ?: stringResource(R.string.watchNextCheckUnknown)
        Text(
            text = nextText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (BuildConfig.DEBUG) {
            WatchIntervalPicker(intervalMinutes) { watchViewModel.setCheckIntervalMinutes(it) }
        }
    }
}

/** Debug-only frequency picker. Options are all valid WorkManager periodic intervals. */
@Composable
private fun WatchIntervalPicker(selectedMinutes: Int, onChange: (Int) -> Unit) {
    val options = listOf(15, 60, 1440)
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(intervalLabel(selectedMinutes))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(intervalLabel(minutes)) },
                    onClick = { expanded = false; onChange(minutes) },
                    leadingIcon = if (minutes == selectedMinutes) {
                        { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null
                )
            }
        }
    }
}

private fun intervalLabel(minutes: Int): String = when (minutes) {
    15 -> "15 min"
    60 -> "1 h"
    1440 -> "24 h"
    else -> "$minutes min"
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
    selected: Boolean,
    onClick: () -> Unit
) {
    val launcherActivityChanged = rule.apps.any { watched ->
        apps.none { it.packageName == watched.packageName && it.activityName == watched.activityName } &&
            apps.any { it.packageName == watched.packageName }
    }
    Surface(
        onClick = onClick,
        shape = CardShape,
        // A clear step above the screen background (surfaceContainerLow) so the card's bounds
        // are visible; inner pills go one step higher again to stay distinct.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Frame the selected rule so it's obvious which one the toolbar acts on.
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
            if (launcherActivityChanged) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = ChangedOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.watchActivityChanged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
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
        shape = CardShape,
        // Same fill as the active card so both rule states read as one consistent card style.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ra = rule.apps.firstOrNull()
                val app = ra?.let { apps.find { a -> a.packageName == it.packageName && a.activityName == it.activityName } }
                AppIcon(app, 28.dp)
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
                // Defensive: never show our own generated pack here. New suggestions already
                // exclude it (WatchChecker), this also hides it for rules completed earlier.
                rule.packs
                    .filter { !it.iconPackPackage.startsWith(IconPackBuilder.PACKAGE_NAME) }
                    .forEach { rp ->
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
        shape = SwatchShape,
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
/** A small rounded pill with a leading 18dp [icon] and an ellipsized [text] label. */
@Composable
private fun Pill(text: String, icon: @Composable () -> Unit) {
    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Text(
                text = text,
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
private fun AppPill(app: PackageInfoStruct?, fallbackPackage: String) {
    Pill(app?.appName ?: fallbackPackage) { AppIcon(app, 18.dp) }
}

/** Pack pill (icon + name) used in the "Watching" / "New icon in" rows. */
@Composable
private fun PackLabel(packPackage: String, name: String?) {
    Pill(name ?: packPackage) { PackIconImage(packPackage, 18.dp) }
}
