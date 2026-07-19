@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.renkinProject.renkin.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.WatchViewModel
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.watch.IconSuggestion
import dev.renkinProject.renkin.data.watch.IconSuggestionCandidate
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shown on the home screen when opened from an icon-watch notification (phase 6):
 * the app's current icon vs the newly found one (with a pack picker if several packs
 * offer one). Confirm stores the icon and toasts to press Build; cancel keeps the
 * suggestion so it can still be applied later from the watch screen.
 */
@Composable
fun WatchApplyModal(suggestionId: Long, onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val viewModel: MainViewModel = hiltViewModel()
    val watchViewModel: WatchViewModel = hiltViewModel()
    val prefs = getPreferences()
    val view = LocalView.current
    val toaster = LocalToaster.current

    var suggestion by remember(suggestionId) { mutableStateOf<IconSuggestion?>(null) }
    var candidates by remember(suggestionId) { mutableStateOf<List<IconSuggestionCandidate>>(emptyList()) }
    var selectedPack by remember(suggestionId) { mutableStateOf<String?>(null) }
    var newIcon by remember(suggestionId) { mutableStateOf<IconPackDrawable?>(null) }
    var generating by remember(suggestionId) { mutableStateOf(false) }
    var candidateChanged by remember(suggestionId) { mutableStateOf(false) }
    var loaded by remember(suggestionId) { mutableStateOf(false) }

    LaunchedEffect(suggestionId) {
        val (s, c) = watchViewModel.loadSuggestion(suggestionId)
        // Drop our own generated pack: a suggestion stored before the WatchChecker filter
        // could still list it, and it must never be offered as an icon source. Filtering
        // here covers the chips, the default selection and the generated preview at once.
        val packCandidates = c.filter { !it.iconPackPackage.startsWith(IconPackBuilder.PACKAGE_NAME) }
        suggestion = s
        candidates = packCandidates
        selectedPack = packCandidates.firstOrNull()?.iconPackPackage
        loaded = true
        if (s == null) onDismiss() // already handled/deleted → nothing to show
    }

    val apps = viewModel.applicationList
    val app = suggestion?.let { s -> apps.find { it.packageName == s.packageName && it.activityName == s.activityName } }

    // (Re)generate the new icon for the selected pack
    LaunchedEffect(selectedPack, suggestion, app) {
        val s = suggestion
        val pack = selectedPack
        val targetApp = app
        val candidate = candidates.find { it.iconPackPackage == pack }
        // App or pack uninstalled since the suggestion fired → nothing to generate
        if (s == null || pack == null || targetApp == null || candidate == null) {
            newIcon = null
            candidateChanged = false
            generating = false
            return@LaunchedEffect
        }
        generating = true
        newIcon = null
        candidateChanged = false
        val validated = withContext(Dispatchers.Default) {
            val options = GenerationOptions.fromPreferences(
                prefs.getPreferencesAfterPendingWrites(), context, override = true
            )
            viewModel.iconFromPack(targetApp, pack, candidate.drawableName, candidate.iconHash, options)
        }
        newIcon = validated.icon
        candidateChanged = validated.sourceChanged
        generating = false
    }

    if (!loaded || suggestion == null) return
    // When opened cold from a notification the app list / packs may still be
    // loading; wait so the modal shows real icons instead of empty placeholders.
    if (!viewModel.applicationsLoaded || !viewModel.iconPackLoaded) return

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watchApplyTitle)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = app?.appName ?: suggestion!!.packageName,
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
                        val pack = packsName(viewModel.iconPacks, candidate.iconPackPackage)
                        FilterChip(
                            selected = selectedPack == candidate.iconPackPackage,
                            onClick = { selectedPack = candidate.iconPackPackage },
                            label = { Text(pack, maxLines = 1) },
                            leadingIcon = { PackIconImage(candidate.iconPackPackage, 18.dp) }
                        )
                    }
                }
                if (candidateChanged) {
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
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
                val applyToast = stringResource(R.string.watchApplyToast)
                DisabledExplanation(
                    enabled = newIcon != null && app != null,
                    message = stringResource(
                        if (candidateChanged) R.string.watchCandidateChanged else R.string.watchApplyDisabledHint
                    )
                ) {
                    FilledIconButton(
                        onClick = {
                            view.performConfirmHaptic()
                            val icon = newIcon
                            val targetApp = app
                            if (icon != null && targetApp != null) {
                                // applyIcon finds the live row by key; a target no longer
                                // in the list is a silent no-op, same as before.
                                viewModel.applyIcon(targetApp, icon, sourcePackName = selectedPack)
                                // Applying handles the rule, so remove it (cascades the suggestion)
                                suggestion?.ruleId?.let { ruleId -> watchViewModel.deleteRule(ruleId) }
                                toaster.show(applyToast)
                            }
                            onDismiss()
                        },
                        enabled = newIcon != null && app != null
                    ) {
                        Icon(Icons.Filled.Check, stringResource(R.string.confirm))
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
