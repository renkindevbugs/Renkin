package dev.alembiconsProject.alembicons.ui

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.Color
import dev.alembiconsProject.alembicons.MainViewModel
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.getPreferencesValue
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildPackFab(isInRefresh: Boolean, expanded: Boolean = true) {
    val viewModel: MainViewModel = hiltViewModel()
    val preferences = getPreferences().getPreferencesValue()
    val view = LocalView.current
    val context = getCurrentContext()
    val toaster = LocalToaster.current

    var showPreview by remember { mutableStateOf(false) }

    val buildStep = viewModel.buildStep

    ExtendedFloatingActionButton(
        onClick = {
            if (isInRefresh) {
                toaster.show(context.getString(R.string.iconsStillGenerated))
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
                viewModel.build(preferences)
            }
        )
    }

    if (buildStep != null) {
        RenkinAlertDialog(
            onDismissRequest = {},
            icon = {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            },
            title = { Text(stringResource(id = R.string.iconPack)) },
            text = {
                // Show only the current step, crossfading between them, instead of an
                // ever-growing log
                Crossfade(targetState = buildStep, label = "buildStep") { step ->
                    Text(
                        text = step ?: "",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { }
        )
    }

}

/**
 * Full-screen review of every icon that will go into the pack (apps that have a
 * created icon), shown before the actual build so the user can judge the set as a
 * whole. The Build button kicks off the real build.
 */
@Composable
fun BuildPackPreview(onDismiss: () -> Unit, onBuild: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val builtKeys = viewModel.builtKeys
    val updatedKeys = viewModel.updatedKeys
    // Sort: new (never built) first → changed (edited this session) second → rest alphabetical.
    val themedApps = viewModel.applicationList
        .filter { it.createdIcon != null }
        .sortedWith(
            compareByDescending<PackageInfoStruct> { "${it.packageName}/${it.activityName}" !in builtKeys }
                .thenByDescending { "${it.packageName}/${it.activityName}" in updatedKeys }
                .thenBy { it.appName.lowercase() }
        )
    val newCount = themedApps.count { "${it.packageName}/${it.activityName}" !in builtKeys }

    // Warn (before building) about calendar apps whose source pack lacks some 1..31 day
    // drawables — those days fall back to a repeated icon instead of rotating.
    val preferences = getPreferences().getPreferencesValue()
    var calendarWarnings by remember { mutableStateOf<List<dev.alembiconsProject.alembicons.apk.ApplicationProvider.CalendarWarning>>(emptyList()) }
    LaunchedEffect(themedApps.size) {
        calendarWarnings = viewModel.calendarWarnings(preferences)
    }

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
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (newCount > 0) {
                        Text(
                            text = "+$newCount",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF34C759),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = stringResource(R.string.buildPreviewCount, themedApps.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (calendarWarnings.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.calendarMissingDaysTitle),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF9500)
                        )
                        calendarWarnings.forEach { warning ->
                            Text(
                                text = stringResource(R.string.calendarMissingDaysApp, warning.appName, warning.missingDays),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

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
                            val key = "${app.packageName}/${app.activityName}"
                            BuildPreviewItem(
                                app = app,
                                isNew = key !in builtKeys,
                                isChanged = key in builtKeys && key in updatedKeys
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
private fun BuildPreviewItem(
    app: PackageInfoStruct,
    isNew: Boolean = false,
    isChanged: Boolean = false
) {
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
            // Green = new (not in last build); orange = edited this session (was already built)
            val dotColor = when {
                isNew -> Color(0xFF34C759)
                isChanged -> Color(0xFFFF9500)
                else -> null
            }
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(dotColor)
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
