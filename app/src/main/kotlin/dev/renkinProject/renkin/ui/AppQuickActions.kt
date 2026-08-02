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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.WatchViewModel
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
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    val watchViewModel: WatchViewModel = hiltViewModel()
    val toaster = LocalToaster.current
    val watchingMessage = stringResource(R.string.quickActionWatchAdded, app.appName)
    val hasIcon = app.createdIcon != null
    // Rules of the ACTIVE profile only (the flow is per-profile), so another profile watching
    // the same app never makes this one look watched.
    val rules by watchViewModel.rules.collectAsState()
    val existingRule = remember(rules, app.key) { watchRuleFor(rules, app) }
    var openWatchScreen by remember { mutableStateOf(false) }

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
                viewModel.refreshSingleIcon(app)
                onDismiss()
            }

            // Saving a rule reads every installed pack to record the icon baseline, which takes a
            // moment: the row stays put and spins instead of the sheet vanishing into nothing.
            // Once a rule exists the row stops offering a duplicate and leads to it instead.
            QuickAction(
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
                busy = watchViewModel.isSavingRule
            ) {
                if (existingRule != null) {
                    openWatchScreen = true
                } else {
                    watchViewModel.saveRule(
                        existing = null,
                        apps = listOf(AppComponent(app.packageName, app.activityName)),
                        watchAll = true,
                        packs = emptyList()
                    ) {
                        toaster.show(watchingMessage)
                        onDismiss()
                    }
                }
            }

            if (hasIcon) {
                QuickAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.quickActionReset),
                    hint = stringResource(R.string.quickActionResetHint),
                    destructive = true
                ) {
                    viewModel.resetIcon(app)
                    onDismiss()
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
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        // A second tap while the work runs would queue another rule; the view model guards that
        // too, but the row must not invite it.
        enabled = !busy,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
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
