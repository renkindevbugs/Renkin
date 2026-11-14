package dev.renkinProject.renkin.feature.buildinstall

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.OptionsViewModel
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.ReduceIconMotionKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.expiredSessionMessage
import dev.renkinProject.renkin.hideWallpaperBehindContent
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.packages.canPostNotifications
import dev.renkinProject.renkin.showWallpaperBehindContent
import dev.renkinProject.renkin.ui.IconFlightEasing
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.OpenAppOptions
import dev.renkinProject.renkin.ui.getCurrentMainActivity
import dev.renkinProject.renkin.ui.performConfirmHaptic

/** One open Pack preview: the profile it reviews and what counted as built/edited when it opened. */
internal class PackPreviewSession(
    val token: ProfileSessionToken,
    val builtKeys: Set<String>,
    val updatedKeys: Set<String>
)

private const val PREVIEW_MORPH_MILLIS = 380
private const val PREVIEW_FADE_MILLIS = 160

/**
 * Hosts Pack preview inside the home window instead of a separate activity. Opening crossfades the
 * home screen into the preview over the real wallpaper while the Build FAB travels into the
 * preview's Build button; closing reverses it. On Build one animation runs above both, and the home
 * screen surfaces under it without any window swap.
 */
@Composable
internal fun PackPreviewHost(
    viewModel: MainViewModel,
    iconPacks: List<IconPack>,
    home: @Composable () -> Unit
) {
    val swirl = remember { BuildSwirlState() }
    val session = viewModel.packPreview
    val options: OptionsViewModel = hiltViewModel()
    val preferences by options.preferenceSnapshot.collectAsStateWithLifecycle()
    val reduceMotion = preferences.getBooleanValue(ReduceIconMotionKey)

    SideEffect { swirl.pillEnabled = !reduceMotion }
    val morph = swirl.previewMorph
    LaunchedEffect(session != null) {
        val spec = tween<Float>(if (reduceMotion) PREVIEW_FADE_MILLIS else PREVIEW_MORPH_MILLIS, easing = IconFlightEasing)
        morph.animateTo(if (session != null) 1f else 0f, spec)
    }
    val previewOnScreen by remember { derivedStateOf { morph.value > 0f } }
    val galaxyRadius = galaxyRadius()
    val density = LocalDensity.current
    val resources = LocalContext.current.resources
    LaunchedEffect(session != null) {
        if (session != null && swirl.packSymbol == null) {
            swirl.packSymbol = withContext(Dispatchers.Default) { loadPackSymbol(resources) }
        }
    }
    LaunchedEffect(session != null, galaxyRadius) {
        if (session == null) swirl.galaxyTexture = null
        else if (swirl.galaxyTexture?.radius != galaxyRadius) {
            swirl.galaxyTexture = withContext(Dispatchers.Default) { renderGalaxyTexture(galaxyRadius, density) }
        }
    }
    WallpaperBehindWindow(shown = session != null || swirl.isRunning || previewOnScreen)
    if (swirl.isRunning) DisposableEffect(Unit) { onDispose { viewModel.holdInstallForBuildAnimation(false) } }

    CompositionLocalProvider(LocalBuildSwirl provides swirl) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (swirl.isRunning) swirl.homeAlpha else 1f - morph.value
                    // No offscreen buffer for the whole screen while it fades.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .background(MaterialTheme.colorScheme.background)
                // Hidden behind the preview: keep screen readers off content the user cannot see.
                .then(if (session != null) Modifier.clearAndSetSemantics { } else Modifier)
        ) { home() }
    }

    // Keeps the last session composed while the preview closes.
    val shown = remember { arrayOfNulls<PackPreviewSession>(1) }
    if (session != null) shown[0] = session
    if (session != null || previewOnScreen) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // During the build animation the preview fades its own chrome and grid.
                    alpha = if (swirl.isRunning) 1f else morph.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
        ) {
            shown[0]?.let { PackPreview(it, viewModel, iconPacks, swirl, animateBuild = !reduceMotion) }
        }
    }
    if (!reduceMotion) BuildPillMorph(swirl)
    BuildSwirlOverlay(swirl, previewIconBackground(preferences), onFinished = viewModel::closePackPreview)
}

@Composable
private fun PackPreview(
    session: PackPreviewSession,
    viewModel: MainViewModel,
    iconPacks: List<IconPack>,
    swirl: BuildSwirlState,
    animateBuild: Boolean
) {
    val context = LocalContext.current
    val view = LocalView.current
    val toaster = LocalToaster.current
    val applicationState by viewModel.applicationState.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val options: OptionsViewModel = hiltViewModel()
    val preferences by options.preferenceSnapshot.collectAsStateWithLifecycle()
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Lets a confirmation reach the user if they leave Renkin while the pack builds; the build
    // never waits for the answer.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // The reviewed icons must stay tied to one profile. A watch deep link can otherwise switch
    // the provider underneath the open preview.
    LaunchedEffect(runtimeState.sessionToken, runtimeState.isProfileSwitching) {
        if (viewModel.packPreview === session && !viewModel.isCurrentSession(session.token)) {
            viewModel.closePackPreview()
            toaster.show(context.getString(session.token.expiredSessionMessage(viewModel.sessionToken)))
        }
    }
    BackHandler(enabled = !swirl.isRunning) { viewModel.closePackPreview() }

    BuildPackPreviewContent(
        applications = applicationState.applications,
        builtKeys = session.builtKeys,
        updatedKeys = session.updatedKeys + viewModel.updatedKeys,
        loadCalendarWarnings = viewModel::calendarWarnings,
        onEditIcon = { editingKey = it.key },
        swirl = swirl,
        onDismiss = { if (!swirl.isRunning) viewModel.closePackPreview() },
        onBuild = {
            if (swirl.isRunning || viewModel.packPreview !== session) return@BuildPackPreviewContent
            // Held before the build starts so even an instant build cannot open the installer mid-animation.
            if (animateBuild) viewModel.holdInstallForBuildAnimation(true)
            if (!viewModel.build(session.token)) {
                viewModel.holdInstallForBuildAnimation(false)
                return@BuildPackPreviewContent
            }
            view.performConfirmHaptic()
            if (animateBuild) swirl.launch() else viewModel.closePackPreview()
            if (PackageVersion.is33OrMore() && !canPostNotifications(context)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
    editingKey?.let { key ->
        val app = applicationState.applications.firstOrNull { it.key == key }
        if (app != null) OpenAppOptions(
            iconPacks = iconPacks,
            app = app,
            themed = preferences.getBooleanValue(ExportThemedKey),
            sourceSession = session.token,
            awaitApply = true,
            onDismiss = { editingKey = null }
        ) else LaunchedEffect(key) { editingKey = null }
    }
}

/** Shows the system wallpaper behind the window for as long as [shown] holds. */
@Composable
private fun WallpaperBehindWindow(shown: Boolean) {
    val window = getCurrentMainActivity().window
    if (shown) DisposableEffect(window) {
        window.showWallpaperBehindContent()
        onDispose { window.hideWallpaperBehindContent() }
    }
}
