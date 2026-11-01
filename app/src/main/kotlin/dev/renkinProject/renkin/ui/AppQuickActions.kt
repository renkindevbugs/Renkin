@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.feature.apptargets.AddAppViewModel
import dev.renkinProject.renkin.feature.apptargets.RemoveAppOutcome
import dev.renkinProject.renkin.feature.watch.WatchRuleSaveStatus
import dev.renkinProject.renkin.feature.watch.WatchScreen
import dev.renkinProject.renkin.feature.watch.WatchViewModel
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dev.renkinProject.renkin.packages.PackageInfoStruct

/**
 * Long-press menu for one app in the home list: the handful of actions that otherwise cost a trip
 * through the fullscreen editor. A plain tap still opens that editor, so anything richer — picking
 * an icon, uploading, the vector editor — deliberately lives there and not here.
 */
@Composable
internal fun AppQuickActionsSheet(
    app: PackageInfoStruct,
    sourceSession: ProfileSessionToken,
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val expected = remember(app.key) { sourceSession }
    LaunchedEffect(runtimeState.sessionToken) {
        if (runtimeState.sessionToken != expected) onDismiss()
    }
    if (runtimeState.sessionToken != expected) return
    val watchViewModel: WatchViewModel = hiltViewModel()
    val toaster = LocalToaster.current
    val watchingMessage = stringResource(R.string.quickActionWatchAdded, app.appName)
    val watchUnavailableMessage = stringResource(R.string.quickActionWatchUnavailable)
    val removedMessage = stringResource(R.string.appRemovedFromList, app.appName)
    val hasIconMessage = stringResource(R.string.quickActionRemoveAppHasIcon)
    val sessionChangedMessage = stringResource(R.string.profileSessionChanged)
    val failedMessage = stringResource(R.string.profileOperationFailed)
    val addAppViewModel: AddAppViewModel = hiltViewModel()
    val hasIcon = app.createdIcon != null
    // Rules of the ACTIVE profile only (the flow is per-profile), so another profile watching
    // the same app never makes this one look watched.
    val rules by watchViewModel.rules.collectAsStateWithLifecycle()
    val existingRule = remember(rules, app.key) { watchRuleFor(rules, app) }
    var openWatchScreen by remember { mutableStateOf(false) }
    val watchSaveState by watchViewModel.ruleSaveState.collectAsStateWithLifecycle()
    var watchRequestId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(watchSaveState, watchRequestId) {
        val outcome = watchSaveState ?: return@LaunchedEffect
        if (outcome.busy || outcome.requestId != watchRequestId) return@LaunchedEffect
        watchViewModel.acknowledgeRuleSave(outcome.requestId)
        when (outcome.status) {
            WatchRuleSaveStatus.SAVED -> {
                toaster.show(watchingMessage)
                onDismiss()
            }
            WatchRuleSaveStatus.SESSION_CHANGED -> onDismiss()
            WatchRuleSaveStatus.SAVING, WatchRuleSaveStatus.NOT_SAVED -> Unit
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )

            QuickAction(
                icon = Icons.Filled.Refresh,
                label = stringResource(
                    if (hasIcon) R.string.quickActionRefresh else R.string.quickActionGenerate
                ),
                hint = stringResource(R.string.quickActionRefreshHint)
            ) {
                viewModel.refreshSingleIcon(app, expected)
                onDismiss()
            }

            // Saving a rule reads every installed pack to record the icon baseline, which takes a
            // moment: the row stays put and spins instead of the sheet vanishing into nothing.
            // Once a rule exists the row stops offering a duplicate and leads to it instead.
            if (app.isAddedTarget) {
                // The watch checker reads the launcher's app list; a dead row still explains itself.
                QuickAction(
                    icon = Icons.Filled.Notifications,
                    label = stringResource(R.string.quickActionWatch),
                    hint = stringResource(R.string.quickActionWatchUnavailableHint),
                    dimmed = true
                ) {
                    toaster.show(watchUnavailableMessage)
                }
            } else QuickAction(
                icon = if (existingRule != null) {
                    Icons.Filled.NotificationsActive
                } else {
                    Icons.Filled.Notifications
                },
                label = stringResource(
                    if (existingRule != null) {
                        R.string.quickActionWatchExisting
                    } else {
                        R.string.quickActionWatch
                    }
                ),
                hint = stringResource(
                    if (existingRule != null) {
                        R.string.quickActionWatchExistingHint
                    } else {
                        R.string.quickActionWatchHint
                    }
                ),
                busy = watchSaveState?.busy == true
            ) {
                if (existingRule != null) {
                    openWatchScreen = true
                } else {
                    val requestId = watchViewModel.newRequestId()
                    val submitted = watchViewModel.saveRule(
                        requestId = requestId,
                        existing = null,
                        apps = listOf(AppComponent(app.packageName, app.activityName)),
                        watchAll = true,
                        packs = emptyList()
                    )
                    if (submitted) watchRequestId = requestId
                }
            }

            if (hasIcon) {
                QuickAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.quickActionReset),
                    hint = stringResource(R.string.quickActionResetHint),
                    destructive = true
                ) {
                    viewModel.resetIcon(app, expected)
                    onDismiss()
                }
            }

            if (app.isAddedTarget) {
                QuickAction(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.quickActionRemoveApp),
                    hint = stringResource(R.string.quickActionRemoveAppHint),
                    destructive = true
                ) {
                    addAppViewModel.remove(expected, app.key) { outcome ->
                        when (outcome) {
                            RemoveAppOutcome.REMOVED -> {
                                toaster.show(removedMessage)
                                onDismiss()
                            }
                            RemoveAppOutcome.HAS_ICON -> toaster.show(hasIconMessage)
                            RemoveAppOutcome.SESSION_CHANGED -> {
                                toaster.show(sessionChangedMessage)
                                onDismiss()
                            }
                            RemoveAppOutcome.FAILED -> toaster.show(failedMessage)
                        }
                    }
                }
            }
        }
    }

    if (openWatchScreen) {
        WatchScreen(highlightRuleId = existingRule?.rule?.id) {
            openWatchScreen = false
            onDismiss()
        }
    }
}

/**
 * The active profile's rule that already watches [app], if any. A rule can cover several apps,
 * so it is the app list that decides, not the rule's name.
 */
internal fun watchRuleFor(
    rules: List<RuleWithDetails>,
    app: PackageInfoStruct
): RuleWithDetails? = rules.firstOrNull { rule ->
    rule.apps.any { it.packageName == app.packageName && it.activityName == app.activityName }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    hint: String,
    destructive: Boolean = false,
    busy: Boolean = false,
    dimmed: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        // A second tap while the work runs would queue another rule; the view model guards that
        // too, but the row must not invite it.
        enabled = !busy,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) 0.38f else 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = if (busy) stringResource(R.string.quickActionWorking) else hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
