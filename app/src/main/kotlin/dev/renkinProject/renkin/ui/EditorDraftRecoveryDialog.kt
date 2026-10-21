package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.EditorDraftRecoveryState
import dev.renkinProject.renkin.EditorDraftRecoveryViewModel
import dev.renkinProject.renkin.EditorSessionViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.feature.profiles.SaveBeforeSwitchDialog
import dev.renkinProject.renkin.ui.theme.IconShape

@Composable
internal fun EditorDraftRecoveryHost(iconPacks: List<IconPack>, showPrompt: Boolean) {
    val owner: EditorDraftRecoveryViewModel = hiltViewModel()
    val editor: EditorSessionViewModel = hiltViewModel()
    val main: MainViewModel = hiltViewModel()
    LaunchedEffect(owner, editor) { owner.connect(editor) }
    val shown by owner.state.collectAsStateWithLifecycle()
    val attachment by editor.recoveryAttachment.collectAsStateWithLifecycle()
    val lifetime by editor.lifetime.collectAsStateWithLifecycle()
    var pendingSwitch by remember { mutableStateOf<Long?>(null) }
    attachment?.let { attached ->
        editor.recoveredSession(attached)?.let { session ->
            OpenAppOptions(iconPacks, session.application, attached.themed, attached.owner,
                recoveredSessionId = attached.sessionId, onSessionInvalidated = { owner.detach(attached) },
                onDismiss = { owner.close(attached) })
        }
    }
    pendingSwitch?.let { profileId ->
        SaveBeforeSwitchDialog(
            onSave = { pendingSwitch = null; main.switchProfile(profileId, saveFirst = true) },
            onDiscard = { pendingSwitch = null; main.switchProfile(profileId, saveFirst = false) },
            onDismiss = { pendingSwitch = null }
        )
        return
    }
    if (showPrompt && lifetime.sessionId == null) {
        EditorDraftRecoveryDialog(shown, owner::restore, owner::retry, owner::discard, owner::postpone) { needs ->
            // The offer comes back as a normal Resume prompt once the owning profile is active.
            if (main.hasUnsavedChanges()) pendingSwitch = needs.profileId
            else main.switchProfile(needs.profileId)
        }
    }
}

@Composable
internal fun EditorDraftWorkingDialog(
    // Only the debug dialog gallery passes this; the real recovery step blocks back.
    onPreviewDismiss: (() -> Unit)? = null
) {
    MessageDialog(
        onDismissRequest = onPreviewDismiss ?: {},
        properties = DialogProperties(dismissOnBackPress = onPreviewDismiss != null, dismissOnClickOutside = false),
        badge = { DialogProgressBadge() },
        title = stringResource(R.string.editorRecoveryWorking),
        actions = {}
    )
}

@Composable
internal fun EditorDraftRecoveryDialog(
    shown: EditorDraftRecoveryState,
    onRestore: (EditorDraftRecoveryState.Offer) -> Unit,
    onRetry: (EditorDraftRecoveryState) -> Unit,
    onDiscard: (EditorDraftRecoveryState) -> Unit,
    onPostpone: (EditorDraftRecoveryState) -> Unit,
    onSwitchProfile: (EditorDraftRecoveryState.NeedsProfile) -> Unit = {}
) {
    if (shown == EditorDraftRecoveryState.Hidden) return
    var confirmDiscard by remember(shown) { mutableStateOf(false) }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.editorRecoveryDiscardTitle),
            text = if (shown is EditorDraftRecoveryState.Offer) {
                stringResource(R.string.editorRecoveryDiscardAppText, shown.applicationName)
            } else stringResource(R.string.editorRecoveryDiscardText),
            confirmLabel = stringResource(R.string.editorRecoveryDiscard),
            icon = Icons.Filled.Delete,
            onConfirm = { confirmDiscard = false; onDiscard(shown) },
            onDismiss = { confirmDiscard = false }
        )
        return
    }
    val discard = { confirmDiscard = true }
    when (shown) {
        EditorDraftRecoveryState.Hidden -> Unit
        EditorDraftRecoveryState.Working -> EditorDraftWorkingDialog()
        is EditorDraftRecoveryState.Offer -> DraftOfferDialog(shown, { onRestore(shown) }, { onPostpone(shown) }, discard)
        is EditorDraftRecoveryState.NeedsProfile -> DraftOtherProfileDialog(shown, { onSwitchProfile(shown) },
            { onPostpone(shown) }, discard)
        // Nothing is left to restore, so there is nothing to ask: clear it the same way Discard would.
        is EditorDraftRecoveryState.Obsolete -> LaunchedEffect(shown) { onDiscard(shown) }
        is EditorDraftRecoveryState.Failed -> if (shown.closingEditor) {
            // The user already closed this editor; deleting its leftover copy needs no second confirmation.
            LaunchedEffect(shown) {
                if (shown.checkpoint != null || shown.resetLease != null) onDiscard(shown) else onPostpone(shown)
            }
        } else {
            DraftProblemDialog(
                icon = Icons.Filled.ErrorOutline, tone = DialogTone.Danger,
                message = stringResource(R.string.editorRecoveryFailed),
                onRetry = { onRetry(shown) }, onLater = { onPostpone(shown) },
                onDiscard = discard.takeIf { shown.checkpoint != null || shown.resetLease != null }
            )
        }
        is EditorDraftRecoveryState.Changed -> DraftProblemDialog(
            icon = Icons.Filled.Info, tone = DialogTone.Neutral,
            message = stringResource(R.string.editorRecoveryChanged),
            onRetry = { onRetry(shown) }, onLater = { onPostpone(shown) }, onDiscard = discard
        )
    }
}

@Composable
private fun DraftOfferDialog(shown: EditorDraftRecoveryState.Offer, onRestore: () -> Unit, onLater: () -> Unit, onDiscard: () -> Unit) {
    val applicationIcon = shown.preview?.applicationIcon
    val savedRender = shown.preview?.savedRender
    MessageDialog(
        onDismissRequest = onLater,
        badge = {
            if (applicationIcon != null) {
                Image(applicationIcon.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp).clip(IconShape))
            } else DialogBadge(Icons.Filled.Restore)
        },
        eyebrow = stringResource(R.string.editorRecoveryTitle),
        title = shown.applicationName,
        supporting = shown.profileName?.let { stringResource(R.string.editorRecoveryProfile, it) },
        actions = {
            DialogPrimaryAction(stringResource(R.string.editorRecoveryRestore), onRestore)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryLater), onLater)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryDiscard), onDiscard, danger = true)
        }
    ) {
        DialogCard {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (savedRender != null) {
                    Image(savedRender.asImageBitmap(),
                        contentDescription = stringResource(R.string.editorRecoveryPreviewDescription, shown.applicationName),
                        modifier = Modifier.size(112.dp), contentScale = ContentScale.Fit)
                } else {
                    DialogCardNote(stringResource(if (shown.preview == null) R.string.editorRecoveryPreviewUnavailable
                        else R.string.editorRecoveryPreviewMissing))
                }
            }
            if (savedRender != null) {
                Text(stringResource(R.string.editorRecoveryPreviewLabel), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DraftOtherProfileDialog(
    shown: EditorDraftRecoveryState.NeedsProfile,
    onSwitch: () -> Unit,
    onLater: () -> Unit,
    onDiscard: () -> Unit
) {
    MessageDialog(
        onDismissRequest = onLater,
        badge = { DialogBadge(Icons.Filled.SwapHoriz) },
        title = stringResource(R.string.editorRecoveryTitle),
        body = AnnotatedString(stringResource(R.string.editorRecoveryOtherProfileText)),
        actions = {
            DialogPrimaryAction(stringResource(R.string.editorRecoverySwitchProfile, shown.profileName), onSwitch)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryLater), onLater)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryDiscard), onDiscard, danger = true)
        }
    ) {
        DialogCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.editorRecoveryProfileLabel), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shown.profileName, style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DraftProblemDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: DialogTone,
    message: String,
    onRetry: () -> Unit,
    onLater: () -> Unit,
    onDiscard: (() -> Unit)?
) {
    MessageDialog(
        onDismissRequest = onLater,
        badge = { DialogBadge(icon, tone) },
        title = stringResource(R.string.editorRecoveryTitle),
        body = AnnotatedString(message),
        actions = {
            DialogPrimaryAction(stringResource(R.string.editorRecoveryRetry), onRetry)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryLater), onLater)
            onDiscard?.let { DialogSecondaryAction(stringResource(R.string.editorRecoveryDiscard), it, danger = true) }
        }
    )
}
