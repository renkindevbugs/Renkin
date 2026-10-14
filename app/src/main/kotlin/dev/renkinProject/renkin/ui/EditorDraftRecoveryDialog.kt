package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.EditorDraftRecoveryState
import dev.renkinProject.renkin.EditorDraftRecoveryViewModel
import dev.renkinProject.renkin.EditorSessionViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.IconShape

@Composable
internal fun EditorDraftRecoveryHost(iconPacks: List<IconPack>, showPrompt: Boolean) {
    val owner: EditorDraftRecoveryViewModel = hiltViewModel()
    val editor: EditorSessionViewModel = hiltViewModel()
    LaunchedEffect(owner, editor) { owner.connect(editor) }
    val shown by owner.state.collectAsStateWithLifecycle()
    val attachment by editor.recoveryAttachment.collectAsStateWithLifecycle()
    val lifetime by editor.lifetime.collectAsStateWithLifecycle()
    attachment?.let { attached ->
        editor.recoveredSession(attached)?.let { session ->
            OpenAppOptions(iconPacks, session.application, attached.themed, attached.owner,
                recoveredSessionId = attached.sessionId, onSessionInvalidated = { owner.detach(attached) },
                onDismiss = { owner.close(attached) })
        }
    }
    if (showPrompt && lifetime.sessionId == null) {
        EditorDraftRecoveryDialog(shown, owner::restore, owner::retry, owner::discard, owner::postpone)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun EditorDraftRecoveryDialog(
    shown: EditorDraftRecoveryState,
    onRestore: (EditorDraftRecoveryState.Offer) -> Unit,
    onRetry: (EditorDraftRecoveryState) -> Unit,
    onDiscard: (EditorDraftRecoveryState) -> Unit,
    onPostpone: (EditorDraftRecoveryState) -> Unit
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
            onConfirm = { confirmDiscard = false; onDiscard(shown) },
            onDismiss = { confirmDiscard = false }
        )
        return
    }
    when (shown) {
        EditorDraftRecoveryState.Hidden -> Unit
        EditorDraftRecoveryState.Working -> RenkinAlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.editorRecoveryWorking)) },
            text = { WavyLoadingBar() }
        )
        is EditorDraftRecoveryState.Offer -> RenkinAlertDialog(
            onDismissRequest = { onPostpone(shown) },
            icon = { Icon(Icons.Filled.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.editorRecoveryTitle)) },
            text = { EditorRecoveryOfferContent(shown) },
            confirmButton = {
                Button(onClick = { onRestore(shown) }) { Text(stringResource(R.string.editorRecoveryRestore)) }
            },
            dismissButton = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onPostpone(shown) }) { Text(stringResource(R.string.editorRecoveryLater)) }
                    TextButton(onClick = { confirmDiscard = true }) {
                        Text(stringResource(R.string.editorRecoveryDiscard), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
        else -> {
            val message = when (shown) {
                is EditorDraftRecoveryState.NeedsProfile -> R.string.editorRecoveryOtherProfile
                is EditorDraftRecoveryState.Obsolete -> R.string.editorRecoveryObsolete
                is EditorDraftRecoveryState.Changed -> R.string.editorRecoveryChanged
                is EditorDraftRecoveryState.Failed ->
                    if (shown.closingEditor) R.string.editorRecoveryCleanupFailed else R.string.editorRecoveryFailed
                else -> error("Unhandled recovery prompt")
            }
            val canDiscard = shown !is EditorDraftRecoveryState.Failed || shown.checkpoint != null || shown.resetLease != null
            val canRetry = shown is EditorDraftRecoveryState.Failed || shown is EditorDraftRecoveryState.Changed
            RenkinAlertDialog(
                onDismissRequest = { onPostpone(shown) },
                title = { Text(stringResource(R.string.editorRecoveryTitle)) },
                text = {
                    Text(if (shown is EditorDraftRecoveryState.NeedsProfile) stringResource(message, shown.profileName)
                        else stringResource(message))
                },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (canRetry) {
                            TextButton(onClick = { onRetry(shown) }) { Text(stringResource(R.string.editorRecoveryRetry)) }
                        }
                        TextButton(onClick = { confirmDiscard = true }, enabled = canDiscard) {
                            Text(stringResource(R.string.editorRecoveryDiscard))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onPostpone(shown) }) { Text(stringResource(R.string.editorRecoveryLater)) }
                }
            )
        }
    }
}

@Composable
private fun EditorRecoveryOfferContent(shown: EditorDraftRecoveryState.Offer, modifier: Modifier = Modifier) {
    val savedRender = shown.preview?.savedRender
    Column(
        modifier = modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val applicationIcon = shown.preview?.applicationIcon
            if (applicationIcon != null) {
                Image(applicationIcon.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(IconShape), contentScale = ContentScale.Fit)
            } else {
                Icon(Icons.Filled.Android, contentDescription = null,
                    modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(shown.applicationName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                shown.profileName?.let { profile ->
                    Text(stringResource(R.string.editorRecoveryProfile, profile),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(152.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                if (savedRender != null) {
                    Image(savedRender.asImageBitmap(),
                        contentDescription = stringResource(R.string.editorRecoveryPreviewDescription, shown.applicationName),
                        modifier = Modifier.size(112.dp), contentScale = ContentScale.Fit)
                } else {
                    Text(stringResource(if (shown.preview == null) R.string.editorRecoveryPreviewUnavailable
                        else R.string.editorRecoveryPreviewMissing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (savedRender != null) {
            Text(stringResource(R.string.editorRecoveryPreviewLabel),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.editorRecoveryText))
    }
}
