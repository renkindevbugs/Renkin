package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.EditorDraftRecoveryState
import dev.renkinProject.renkin.EditorDraftRecoveryViewModel
import dev.renkinProject.renkin.EditorSessionViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack

@Composable
internal fun EditorDraftRecoveryHost(iconPacks: List<IconPack>, showPrompt: Boolean) {
    val owner: EditorDraftRecoveryViewModel = hiltViewModel()
    val editor: EditorSessionViewModel = hiltViewModel()
    LaunchedEffect(owner, editor) { owner.connect(editor) }
    val shown by owner.state.collectAsStateWithLifecycle()
    val attachment by editor.recoveryAttachment.collectAsStateWithLifecycle()
    attachment?.let { attached ->
        editor.recoveredSession(attached)?.let { session ->
            OpenAppOptions(iconPacks, session.application, attached.themed, attached.owner,
                recoveredSessionId = attached.sessionId, onSessionInvalidated = { owner.detach(attached) },
                onDismiss = { owner.close(attached) })
        }
    }
    if (showPrompt) EditorDraftRecoveryDialog(shown, owner::restore, owner::retry, owner::discard, owner::postpone)
}

@Composable
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
            text = stringResource(R.string.editorRecoveryDiscardText),
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
            title = { Text(stringResource(R.string.editorRecoveryTitle)) },
            text = { Text(stringResource(R.string.editorRecoveryText, shown.applicationName)) },
            confirmButton = {
                TextButton(onClick = { onRestore(shown) }) { Text(stringResource(R.string.editorRecoveryRestore)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onPostpone(shown) }) { Text(stringResource(R.string.editorRecoveryLater)) }
                    TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.editorRecoveryDiscard)) }
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
