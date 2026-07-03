package dev.renkinProject.renkin.ui

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
import androidx.compose.material3.LinearWavyProgressIndicator
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
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.WallpaperPreviewActivity
import dev.renkinProject.renkin.apk.ApplicationProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.ui.theme.ChangedOrange
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.packages.PackageInfoStruct

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildPackFab(isInRefresh: Boolean, expanded: Boolean = true) {
    val viewModel: MainViewModel = hiltViewModel()
    val preferences = getPreferences().getPreferencesValue()
    val view = LocalView.current
    val context = getCurrentContext()
    val toaster = LocalToaster.current

    val buildStep = viewModel.buildStep

    // The preview lives in its own activity (WallpaperPreviewActivity) so the real wallpaper can
    // show behind it; RESULT_OK = the user pressed Build there.
    val previewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            view.performConfirmHaptic()
            viewModel.build(preferences)
        }
    }

    ExtendedFloatingActionButton(
        onClick = {
            if (isInRefresh) {
                toaster.show(context.getString(R.string.iconsStillGenerated))
                return@ExtendedFloatingActionButton
            }

            // Review the whole pack before committing to a build
            view.performTapHaptic()
            previewLauncher.launch(
                Intent(context, WallpaperPreviewActivity::class.java)
                    .putStringArrayListExtra(WallpaperPreviewActivity.EXTRA_BUILT_KEYS, ArrayList(viewModel.builtKeys))
                    .putStringArrayListExtra(WallpaperPreviewActivity.EXTRA_UPDATED_KEYS, ArrayList(viewModel.updatedKeys))
            )
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

    if (buildStep != null) {
        RenkinAlertDialog(
            onDismissRequest = {},
            icon = {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            },
            title = { Text(stringResource(id = R.string.iconPack)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    // The per-app phase is the long one — show it as a determinate bar with a
                    // count so the dialog visibly moves instead of sitting on one step text.
                    val progress = viewModel.buildProgress
                    if (progress != null) {
                        val (done, total) = progress
                        LinearWavyProgressIndicator(
                            progress = { if (total == 0) 0f else done / total.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                        Text(
                            text = "$done / $total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = { }
        )
    }

}

/**
 * Full-screen review of every icon that will go into the pack (apps that have a
 * created icon), shown before the actual build so the user can judge the set as a
 * whole. Hosted by [dev.renkinProject.renkin.WallpaperPreviewActivity], whose
 * windowShowWallpaper theme puts the real wallpaper behind the translucent scrim here.
 * The Build button reports back (RESULT_OK) and the launching side runs the build.
 */
@Composable
fun BuildPackPreviewContent(
    applications: List<PackageInfoStruct>,
    builtKeys: Set<String>,
    updatedKeys: Set<String>,
    loadCalendarWarnings: suspend (Preferences) -> List<ApplicationProvider.CalendarWarning>,
    onDismiss: () -> Unit,
    onBuild: () -> Unit
) {
    // Sort: new (never built) first → changed (edited this session) second → rest alphabetical.
    val themedApps = applications
        .filter { it.createdIcon != null }
        .sortedWith(
            compareByDescending<PackageInfoStruct> { it.key !in builtKeys }
                .thenByDescending { it.key in updatedKeys }
                .thenBy { it.appName.lowercase() }
        )
    val newCount = themedApps.count { it.key !in builtKeys }

    // Warn (before building) about calendar apps whose source pack lacks some 1..31 day
    // drawables — those days fall back to a repeated icon instead of rotating.
    val preferences = getPreferences().getPreferencesValue()
    var calendarWarnings by remember { mutableStateOf<List<ApplicationProvider.CalendarWarning>>(emptyList()) }
    LaunchedEffect(themedApps.size) {
        calendarWarnings = loadCalendarWarnings(preferences)
    }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            // Fully transparent (Icon Pack Studio style): the wallpaper shows untouched behind
            // the icon grid; only the top chrome and the bottom build bar get an opaque surface.
            color = Color.Transparent
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .statusBarsPadding()
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
                            color = AddedGreen,
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
                            color = ChangedOrange
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
                }

                if (themedApps.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.buildPreviewEmpty),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
                            ),
                            color = Color.White,
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
                        items(themedApps, key = { it.key }) { app ->
                            val key = app.key
                            BuildPreviewItem(
                                app = app,
                                isNew = key !in builtKeys,
                                isChanged = key in builtKeys && key in updatedKeys
                            )
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .navigationBarsPadding()
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DisabledExplanation(
                        enabled = themedApps.isNotEmpty(),
                        message = stringResource(R.string.buildDisabledHint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = onBuild,
                            enabled = themedApps.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Build, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.buildIconPack))
                        }
                    }
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
                isNew -> AddedGreen
                isChanged -> ChangedOrange
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
        // Launcher-style label: white with a soft shadow, readable on any wallpaper (the grid
        // sits directly on the transparent, wallpaper-showing area).
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall.copy(
                shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
