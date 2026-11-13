@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.renkinProject.renkin.feature.buildinstall

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import dev.renkinProject.renkin.data.ReduceIconMotionKey
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import dev.renkinProject.renkin.ui.tappableIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.ui.theme.OnWallpaperTextShadow
import dev.renkinProject.renkin.ui.theme.IconShape
import dev.renkinProject.renkin.WallpaperPreviewActivity
import dev.renkinProject.renkin.profileSession
import dev.renkinProject.renkin.withProfileSession
import dev.renkinProject.renkin.expiredSessionMessage
import dev.renkinProject.renkin.apk.ApplicationProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.OptionsViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.DisabledExplanation
import dev.renkinProject.renkin.ui.KeepScreenOn
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.RenkinAlertDialog
import dev.renkinProject.renkin.ui.drawVerticalScrollbar
import dev.renkinProject.renkin.ui.getCurrentContext
import dev.renkinProject.renkin.ui.performConfirmHaptic
import dev.renkinProject.renkin.ui.performTapHaptic
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.ui.theme.ChangedOrange
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.packages.canPostNotifications

internal data class BuildPreviewApps(
    val applications: List<PackageInfoStruct>,
    val newCount: Int
)

private data class BuildPreviewSortEntry(
    val application: PackageInfoStruct,
    val isNew: Boolean,
    val isUpdated: Boolean,
    val sortName: String
)

internal fun buildPreviewApps(
    applications: List<PackageInfoStruct>,
    builtKeys: Set<String>,
    updatedKeys: Set<String>
): BuildPreviewApps {
    var newCount = 0
    val sortedApps = applications.asSequence()
        .filter { it.createdIcon != null }
        .map { application ->
            val isNew = application.key !in builtKeys
            if (isNew) newCount++
            BuildPreviewSortEntry(
                application = application,
                isNew = isNew,
                isUpdated = application.key in updatedKeys,
                sortName = application.appName.lowercase()
            )
        }
        .sortedWith(
            compareByDescending<BuildPreviewSortEntry> { it.isNew }
                .thenByDescending { it.isUpdated }
                .thenBy { it.sortName }
        )
        .map { it.application }
        .toList()

    return BuildPreviewApps(sortedApps, newCount)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildPackFab(isInRefresh: Boolean, expanded: Boolean = true, modifier: Modifier = Modifier) {
    val viewModel: MainViewModel = hiltViewModel()
    val view = LocalView.current
    val context = getCurrentContext()
    val toaster = LocalToaster.current

    val buildStep = viewModel.buildStep
    val ready = viewModel.buildReady
    val buildProgress = viewModel.buildProgress
    val profileAction by viewModel.profileActionProgress.collectAsStateWithLifecycle()
    if (profileAction?.profileTarget != null) return
    val options: OptionsViewModel = hiltViewModel()
    val preferences by options.preferenceSnapshot.collectAsStateWithLifecycle()
    val animateBuild = !preferences.getBooleanValue(ReduceIconMotionKey)
    val landing = rememberBuildIconLanding(viewModel)
    val landingIconBackground = previewIconBackground(preferences)

    // The preview lives in its own activity (WallpaperPreviewActivity) so the real wallpaper can
    // show behind it; RESULT_OK = the user pressed Build there.
    // Lets a confirmation reach the user if they leave Renkin while the pack builds; the build
    // never waits for the answer.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var previewOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    val previewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        PackPreviewEditorOwners.release(previewOwnerId)
        previewOwnerId = null
        if (result.resultCode == Activity.RESULT_OK) {
            val expected = result.data?.profileSession() ?: return@rememberLauncherForActivityResult
            if (result.data?.getBooleanExtra(WallpaperPreviewActivity.EXTRA_BUILD_STARTED, false) != true) {
                view.performConfirmHaptic()
                viewModel.build(expected)
            }
            BuildIconStarts.from(result.data)?.let(landing::catch)
            if (PackageVersion.is33OrMore() && !canPostNotifications(context)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (result.data?.getBooleanExtra(WallpaperPreviewActivity.EXTRA_SESSION_LOST, false) == true) {
            // The process was killed while the preview was in front: it closed itself rather
            // than review a state the user never saw. Say so instead of looking like a no-op.
            toaster.show(context.getString(R.string.buildPreviewSessionLost))
        } else if (result.data?.getBooleanExtra(WallpaperPreviewActivity.EXTRA_SESSION_CHANGED, false) == true) {
            val expected = result.data?.profileSession() ?: return@rememberLauncherForActivityResult
            toaster.show(context.getString(expected.expiredSessionMessage(viewModel.sessionToken)))
        }
    }

    ExtendedFloatingActionButton(
        onClick = {
            if (ready) {
                viewModel.installReadyPack()
                return@ExtendedFloatingActionButton
            }
            if (viewModel.packPreviewOpen) return@ExtendedFloatingActionButton
            if (viewModel.buildBusy) {
                toaster.show(context.getString(R.string.buildOperationPending))
                return@ExtendedFloatingActionButton
            }
            if (isInRefresh) {
                toaster.show(context.getString(R.string.iconsStillGenerated))
                return@ExtendedFloatingActionButton
            }
            if (viewModel.isProfileSwitching) {
                toaster.show(context.getString(R.string.profileStillLoading))
                return@ExtendedFloatingActionButton
            }

            // Review the whole pack before committing to a build
            view.performTapHaptic()
            val editorOwner = PackPreviewEditorOwners.register(viewModel)
            previewOwnerId = editorOwner
            previewLauncher.launch(
                Intent(context, WallpaperPreviewActivity::class.java)
                    .putExtra(WallpaperPreviewActivity.EXTRA_EDITOR_OWNER, editorOwner)
                    .withProfileSession(viewModel.sessionToken)
                    .putStringArrayListExtra(WallpaperPreviewActivity.EXTRA_BUILT_KEYS, ArrayList(viewModel.builtKeys))
                    .putStringArrayListExtra(WallpaperPreviewActivity.EXTRA_UPDATED_KEYS, ArrayList(viewModel.updatedKeys))
                    .apply { if (animateBuild) putAnimateBuild() }
            )
        },
        icon = {
            if (buildStep != null) {
                if (buildProgress != null) {
                    CircularWavyProgressIndicator(
                        progress = { if (buildProgress.second <= 0) 0f else (buildProgress.first.toFloat() / buildProgress.second).coerceIn(0f, 1f) },
                        modifier = Modifier.size(28.dp)
                    )
                } else LoadingIndicator(modifier = Modifier.size(28.dp))
            } else Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null
            )
        },
        text = {
            Column {
                Text(stringResource(if (ready) R.string.buildReadyInstall else if (buildStep != null) R.string.buildNotificationTitle else R.string.buildIconPack))
                if (buildStep != null) {
                    Text(
                        text = buildProgress?.let { stringResource(R.string.buildNotificationCount, it.first, it.second) }
                            ?: buildStep,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.widthIn(min = 100.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        expanded = expanded || buildStep != null || ready,
        modifier = modifier.buildIconLanding(landing, landingIconBackground),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )

}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BuildProgressDialog(
    buildStep: String,
    progress: Pair<Int, Int>?,
    // Only the debug dialog gallery passes this; the real build dialog cannot be dismissed.
    onPreviewDismiss: (() -> Unit)? = null
) {
    RenkinAlertDialog(
        onDismissRequest = onPreviewDismiss ?: {},
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
                        text = step,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // The per-app phase is the long one — show it as a determinate bar with a
                // count so the dialog visibly moves instead of sitting on one step text.
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

/**
 * Full-screen review of every icon that will go into the pack (apps that have a
 * created icon), shown before the actual build so the user can judge the set as a
 * whole. Hosted by [dev.renkinProject.renkin.WallpaperPreviewActivity], whose
 * windowShowWallpaper theme puts the real wallpaper behind the translucent scrim here.
 * The Build button reports back (RESULT_OK) and the launching side runs the build.
 */
@Composable
internal fun BuildPackPreviewContent(
    applications: List<PackageInfoStruct>,
    builtKeys: Set<String>,
    updatedKeys: Set<String>,
    loadCalendarWarnings: suspend (Preferences) -> List<ApplicationProvider.CalendarWarning>,
    onEditIcon: ((PackageInfoStruct) -> Unit)? = null,
    iconSource: BuildIconSource? = null,
    onDismiss: () -> Unit,
    onBuild: () -> Unit
) {
    // Sort: new (never built) first → changed (edited this session) second → rest alphabetical.
    val previewApps by remember(applications, builtKeys, updatedKeys) {
        derivedStateOf {
            buildPreviewApps(applications, builtKeys, updatedKeys)
        }
    }
    val openingKeys = rememberSaveable { previewApps.applications.map { it.key } }
    val openingOrder = remember(openingKeys) { openingKeys.mapIndexed { index, key -> key to index }.toMap() }
    val themedApps = remember(previewApps.applications, openingOrder) {
        previewApps.applications.sortedBy { openingOrder[it.key] ?: Int.MAX_VALUE }
    }
    val newCount = previewApps.newCount
    if (iconSource != null) SideEffect { iconSource.apps = themedApps }

    // Warn (before building) about calendar apps whose source pack lacks some 1..31 day
    // drawables — those days fall back to a repeated icon instead of rotating.
    val optionsViewModel: OptionsViewModel = hiltViewModel()
    val preferences by optionsViewModel.preferenceSnapshot.collectAsStateWithLifecycle()
    val themedBackground = previewIconBackground(preferences)
    var calendarWarnings by remember { mutableStateOf<List<ApplicationProvider.CalendarWarning>>(emptyList()) }
    LaunchedEffect(themedApps.size, preferences) {
        calendarWarnings = loadCalendarWarnings(preferences)
    }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            // Fully transparent (Icon Pack Studio style): the wallpaper shows untouched behind
            // the icon grid; only the top chrome and the bottom build bar get an opaque surface.
            color = Color.Transparent
        ) {
            Column(Modifier.fillMaxSize().then(if (iconSource != null) Modifier.graphicsLayer { alpha = iconSource.chromeAlpha } else Modifier)) {
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
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = stringResource(R.string.buildPreviewTitle),
                        style = MaterialTheme.typography.titleMediumEmphasized,
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
                HorizontalDivider()

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
                    HorizontalDivider()
                }
                }

                if (themedApps.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.buildPreviewEmpty),
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = OnWallpaperTextShadow),
                            color = Color.White,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                } else {
                    val previewGridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        state = previewGridState,
                        columns = GridCells.Adaptive(72.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .drawVerticalScrollbar(previewGridState)
                            .then(if (iconSource != null) Modifier.onGloballyPositioned(iconSource::onGridPlaced).graphicsLayer { alpha = if (iconSource.isFlying) 0f else 1f } else Modifier),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(themedApps, key = { it.key }) { app ->
                            val key = app.key
                            if (iconSource != null) DisposableEffect(iconSource, key) { onDispose { iconSource.onIconRemoved(key) } }
                            BuildPreviewItem(
                                app = app,
                                isNew = key !in builtKeys,
                                isChanged = key in builtKeys && key in updatedKeys,
                                themedBackground = themedBackground,
                                onEdit = onEditIcon?.let { edit -> { edit(app) } },
                                onIconPlaced = iconSource?.let { source -> { coordinates -> source.onIconPlaced(key, coordinates) } }
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
                    HorizontalDivider()
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
        iconSource?.let { BuildIconDepartureOverlay(it, themedBackground) }
    }
}

@Composable
private fun BuildPreviewItem(
    app: PackageInfoStruct,
    isNew: Boolean = false,
    isChanged: Boolean = false,
    themedBackground: Color? = null,
    onEdit: (() -> Unit)? = null,
    onIconPlaced: ((LayoutCoordinates) -> Unit)? = null
) {
    Column(
        modifier = if (onEdit == null) Modifier else Modifier.tappableIcon(onEdit),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            app.createdIcon?.let { icon ->
                Image(
                    painter = icon.getPainter(),
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(56.dp)
                        .then(if (onIconPlaced != null) Modifier.onGloballyPositioned(onIconPlaced) else Modifier)
                        .clip(IconShape)
                        .background(themedBackground ?: Color.Transparent)
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
            style = MaterialTheme.typography.labelSmall.copy(shadow = OnWallpaperTextShadow),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
