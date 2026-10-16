package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.LostUnsavedWork
import dev.renkinProject.renkin.data.UnsavedSessionMarker

@Composable
internal fun LostUnsavedSessionDialog(
    lost: UnsavedSessionMarker,
    profileName: String?,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val edited = lost.editedCount.takeIf { it > 0 }
        ?.let { pluralStringResource(R.plurals.lostUnsavedEditedCount, it, it) }
    val refreshed = lost.refreshedCount.takeIf { it > 0 }
        ?.let { pluralStringResource(R.plurals.lostUnsavedRefreshedCount, it, it) }
    val counts = if (edited != null && refreshed != null) {
        stringResource(R.string.lostUnsavedCountsBoth, edited, refreshed)
    } else edited ?: refreshed ?: return
    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.lostUnsavedTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (profileName != null) boldStringResource(R.string.lostUnsavedText, counts, profileName)
                    else boldStringResource(R.string.lostUnsavedTextNoProfile, counts)
                )
                Text(stringResource(R.string.lostUnsavedTip))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = if (canRefresh) {
            { TextButton(onClick = onRefresh) { Text(stringResource(R.string.lostUnsavedRefresh)) } }
        } else null
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RestoreLostIconsDialog(
    lost: LostUnsavedWork,
    profileName: String,
    onRestore: () -> Unit,
    onRefresh: () -> Unit,
    onLater: () -> Unit,
    onDiscard: () -> Unit
) {
    var confirmDiscard by remember(lost) { mutableStateOf(false) }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.lostIconsDiscardTitle),
            text = stringResource(R.string.lostIconsDiscardText),
            confirmLabel = stringResource(R.string.editorRecoveryDiscard),
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false }
        )
        return
    }
    val restorable = pluralStringResource(R.plurals.lostUnsavedEditedCount, lost.restorableCount, lost.restorableCount)
    val refreshed = lost.refreshedCount
    val dropped = lost.offer?.droppedCount ?: 0
    RenkinAlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Filled.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.lostIconsRestoreTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(boldStringResource(R.string.lostIconsRestoreText, restorable, profileName))
                if (dropped > 0) {
                    Text(stringResource(
                        R.string.lostIconsDroppedNote,
                        pluralStringResource(R.plurals.lostUnsavedEditedCount, dropped, dropped)
                    ))
                }
                if (refreshed > 0) {
                    Text(
                        stringResource(
                            R.string.lostIconsRefreshedNote,
                            pluralStringResource(R.plurals.lostUnsavedRefreshedCount, refreshed, refreshed)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onRestore) { Text(stringResource(R.string.lostIconsRestore)) }
        },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (refreshed > 0) {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.lostUnsavedRefresh)) }
                }
                TextButton(onClick = onLater) { Text(stringResource(R.string.editorRecoveryLater)) }
                TextButton(onClick = { confirmDiscard = true }) {
                    Text(stringResource(R.string.editorRecoveryDiscard), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
