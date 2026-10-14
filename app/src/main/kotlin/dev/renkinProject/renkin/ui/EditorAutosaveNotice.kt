package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.EditorAutosaveState
import dev.renkinProject.renkin.EditorAutosaveStatus
import dev.renkinProject.renkin.R

@Composable
internal fun EditorAutosaveNotice(
    shown: EditorAutosaveState,
    onRetry: (EditorAutosaveState) -> Unit,
    onDiscard: (EditorAutosaveState) -> Unit,
    onReview: (EditorAutosaveState) -> Unit
) {
    if (shown.status != EditorAutosaveStatus.BLOCKED && shown.status != EditorAutosaveStatus.FAILED) return
    var confirmDiscard by remember(shown) { mutableStateOf(false) }
    var confirmReview by remember(shown) { mutableStateOf(false) }
    val message = when (shown.status) {
        EditorAutosaveStatus.BLOCKED -> R.string.editorAutosaveBlocked
        else -> R.string.editorAutosaveFailed
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.labelSmall,
            color = if (shown.status == EditorAutosaveStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown.status == EditorAutosaveStatus.BLOCKED) {
                TextButton(onClick = { confirmReview = true }) {
                    Text(stringResource(R.string.editorAutosaveReview))
                }
            } else {
                TextButton(onClick = { onRetry(shown) }) { Text(stringResource(R.string.editorRecoveryRetry)) }
            }
            TextButton(onClick = { confirmDiscard = true },
                enabled = shown.checkpoint != null || shown.resetLease != null) {
                Text(stringResource(R.string.editorRecoveryDiscard))
            }
        }
    }
    if (confirmReview) ConfirmDialog(
        title = stringResource(R.string.editorAutosaveReview),
        text = stringResource(R.string.editorAutosaveReviewText),
        confirmLabel = stringResource(R.string.editorAutosaveReview),
        onConfirm = { confirmReview = false; onReview(shown) },
        onDismiss = { confirmReview = false }
    )
    if (confirmDiscard) ConfirmDialog(
        title = stringResource(R.string.editorRecoveryDiscardTitle),
        text = shown.draftApplicationName?.let { stringResource(R.string.editorAutosaveDiscardNamedText, it) }
            ?: stringResource(R.string.editorAutosaveDiscardText),
        confirmLabel = stringResource(R.string.editorRecoveryDiscard),
        onConfirm = { confirmDiscard = false; onDiscard(shown) },
        onDismiss = { confirmDiscard = false }
    )
}
