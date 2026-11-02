@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.renkinProject.renkin.feature.watch

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.watch.WatchApplySnapshot
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.data.watch.IconSuggestionCandidate
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.ui.DisabledExplanation
import dev.renkinProject.renkin.ui.PackIconImage
import dev.renkinProject.renkin.ui.RenkinAlertDialog
import dev.renkinProject.renkin.ui.performConfirmHaptic
import dev.renkinProject.renkin.ui.rememberAppBitmap

private data class WatchPreviewKey(
    val snapshot: WatchApplySnapshot?,
    val candidate: IconSuggestionCandidate?,
    val app: PackageInfoStruct?,
    val session: ProfileSessionToken
)
private data class WatchPreview(val key: WatchPreviewKey, val icon: IconPackDrawable?, val sourceChanged: Boolean)

/**
 * Shown on the home screen when opened from an icon-watch notification (phase 6):
 * the app's current icon vs the newly found one (with a pack picker if several packs
 * offer one). Confirm stores the icon and toasts to press Build; cancel keeps the
 * suggestion so it can still be applied later from the watch screen.
 */
@Composable
fun WatchApplyModal(suggestionId: Long, onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val iconPackState by viewModel.iconPackState.collectAsStateWithLifecycle()
    val applicationState by viewModel.applicationState.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val watchViewModel: WatchViewModel = hiltViewModel()
    val view = LocalView.current
    val suggestionSession = remember(suggestionId) { runtimeState.sessionToken }

    var snapshot by remember(suggestionId) { mutableStateOf<WatchApplySnapshot?>(null) }
    var selectedPack by remember(suggestionId) { mutableStateOf<String?>(null) }
    var loaded by remember(suggestionId) { mutableStateOf(false) }
    val applyStates by viewModel.watchApplyStates.collectAsStateWithLifecycle()
    val operation = applyStates[suggestionId]?.takeIf {
        it.request.snapshot.storageEpoch == viewModel.watchStorageEpoch && it.request.session.profileId == suggestionSession.profileId
    }
    val applying = operation?.busy == true
    val cleanupFailed = operation?.phase == WatchApplyPhase.APPLIED_CLEANUP_FAILED
    val hasReceipt = operation != null && operation.phase != WatchApplyPhase.APPLYING && operation.phase != WatchApplyPhase.NOT_APPLIED
    // Cleanup may already have removed Room rows before a notification failure. Keep its receipt visible.
    val displayedSnapshot = if (hasReceipt) operation.request.snapshot else snapshot
    val suggestion = displayedSnapshot?.suggestion
    val candidates = displayedSnapshot?.candidates.orEmpty().filter { !IconPackBuilder.isOwnPack(it.iconPackPackage) }
    val displayedPack = if (hasReceipt) operation.request.candidate.iconPackPackage else selectedPack
    LaunchedEffect(operation?.request?.id, operation?.phase) {
        val result = operation ?: return@LaunchedEffect
        if (result.phase == WatchApplyPhase.APPLIED_CLEANED || result.phase == WatchApplyPhase.NOT_APPLIED) {
            if (viewModel.consumeWatchApply(result.request.id, suggestionId) &&
                (result.phase == WatchApplyPhase.APPLIED_CLEANED || loaded && snapshot == null)) onDismiss()
        }
    }
    LaunchedEffect(runtimeState.sessionToken, applying) {
        if (runtimeState.ready && runtimeState.sessionToken != suggestionSession && !applying) onDismiss()
    }
    LaunchedEffect(suggestionId) {
        val target = watchViewModel.loadSuggestion(suggestionId)
        snapshot = target
        selectedPack = target?.candidates?.firstOrNull { !IconPackBuilder.isOwnPack(it.iconPackPackage) }?.iconPackPackage
        loaded = true
        if (target == null && viewModel.watchApplyStates.value[suggestionId] == null) onDismiss()
    }

    val apps = applicationState.applications
    val app = suggestion?.let { s -> apps.find { it.packageName == s.packageName && it.activityName == s.activityName } }
    val previewKey = WatchPreviewKey(displayedSnapshot, candidates.find { it.iconPackPackage == displayedPack }, app, suggestionSession)
    var renderedPreview by remember(suggestionId) { mutableStateOf<WatchPreview?>(null) }
    val preview = renderedPreview?.takeIf { it.key == previewKey }
    val newIcon = if (hasReceipt) operation.request.icon else preview?.icon
    val generating = !hasReceipt && previewKey.app != null && previewKey.candidate != null && preview == null
    val candidateChanged = preview?.sourceChanged == true
    LaunchedEffect(previewKey, hasReceipt) {
        if (hasReceipt) return@LaunchedEffect
        val candidate = previewKey.candidate ?: return@LaunchedEffect
        val targetApp = previewKey.app ?: return@LaunchedEffect
        val validated = viewModel.iconFromPack(targetApp, candidate)
        renderedPreview = WatchPreview(previewKey, validated.icon, validated.sourceChanged)
    }

    if (!loaded || suggestion == null) return
    // When opened cold from a notification the app list / packs may still be
    // loading; wait so the modal shows real icons instead of empty placeholders.
    if (!applicationState.applicationsLoaded || !iconPackState.loaded) return

    RenkinAlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(stringResource(R.string.watchApplyTitle)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = app?.appName ?: suggestion.packageName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                val currentBitmap = app?.let { rememberAppBitmap(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ComparePreview(currentBitmap, null, loading = app != null && currentBitmap == null)
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ComparePreview(null, newIcon, loading = generating)
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
                        val pack = packsName(iconPackState.packs, candidate.iconPackPackage)
                        FilterChip(
                            selected = displayedPack == candidate.iconPackPackage,
                            onClick = { selectedPack = candidate.iconPackPackage },
                            enabled = !applying && !cleanupFailed,
                            label = { Text(pack, maxLines = 1) },
                            leadingIcon = { PackIconImage(candidate.iconPackPackage, 18.dp) }
                        )
                    }
                }
                if (cleanupFailed) {
                    Text(stringResource(R.string.watchApplyCleanupFailed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp))
                }
                if (candidateChanged && !cleanupFailed) {
                    Text(
                        text = stringResource(R.string.watchCandidateChanged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(
                    onClick = onDismiss,
                    enabled = !applying,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
                DisabledExplanation(
                    enabled = (cleanupFailed || newIcon != null && app != null) && !applying,
                    message = stringResource(
                        if (candidateChanged) R.string.watchCandidateChanged else R.string.watchApplyDisabledHint
                    )
                ) {
                    FilledIconButton(
                        onClick = {
                            view.performConfirmHaptic()
                            if (cleanupFailed) {
                                viewModel.retryWatchCleanup(operation.request.id, suggestionId)
                            } else {
                                val reviewed = preview
                                val target = reviewed?.key?.snapshot
                                val candidate = reviewed?.key?.candidate
                                val targetApp = reviewed?.key?.app
                                val icon = reviewed?.icon
                                if (target != null && candidate != null && targetApp != null && icon != null) {
                                    viewModel.applyWatchSuggestion(target, candidate, targetApp, icon, suggestionSession)
                                }
                            }
                        },
                        enabled = (cleanupFailed || newIcon != null && app != null) && !applying
                    ) {
                        Icon(if (cleanupFailed) Icons.Filled.Refresh else Icons.Filled.Check,
                            stringResource(if (cleanupFailed) R.string.watchRetryCleanup else R.string.confirm))
                    }
                }
            }
        }
    )
}

/** A small framed preview cell: a bitmap (current), a drawable (new), a spinner, or "unavailable". */
@Composable
private fun ComparePreview(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    icon: IconPackDrawable?,
    loading: Boolean
) {
    // Crossfade between states so the new icon fades in after the spinner instead
    // of snapping in once generation finishes
    val state = when {
        bitmap != null -> "bitmap"
        icon != null -> "icon"
        loading -> "loading"
        else -> "empty"
    }
    Surface(
        modifier = Modifier.size(72.dp),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Crossfade(targetState = state, label = "comparePreview") { s ->
            // fillMaxSize so the cell centers small content (the spinner / empty icon);
            // without it the Box shrinks to its content and Crossfade pins it top-left.
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                when (s) {
                    "bitmap" -> bitmap?.let { Image(BitmapPainter(it), null, Modifier.fillMaxSize()) }
                    "icon" -> icon?.let { Image(it.getPainter(), null, Modifier.fillMaxSize()) }
                    "loading" -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    else -> Icon(
                        Icons.Filled.ImageNotSupported, null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

private fun packsName(packs: List<IconPack>, packageName: String): String =
    packs.find { it.packageName == packageName }?.applicationName ?: packageName
