package dev.renkinProject.renkin.ui

import androidx.compose.material.icons.filled.Delete
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
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Warning, DialogTone.Danger) },
        title = stringResource(R.string.lostUnsavedTitle),
        body = if (profileName != null) boldStringResource(R.string.lostUnsavedText, counts, profileName)
            else boldStringResource(R.string.lostUnsavedTextNoProfile, counts),
        actions = {
            DialogPrimaryAction(stringResource(R.string.ok), onDismiss)
            if (canRefresh) DialogSecondaryAction(stringResource(R.string.lostUnsavedRefresh), onRefresh)
        }
    ) {
        DialogCard { DialogCardNote(stringResource(R.string.lostUnsavedTip)) }
    }
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
            icon = Icons.Filled.Delete,
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false }
        )
        return
    }
    val restorable = lost.restorableCount
    val refreshed = lost.refreshedCount
    val dropped = lost.offer?.droppedCount ?: 0
    MessageDialog(
        onDismissRequest = onLater,
        badge = { DialogBadge(Icons.Filled.Restore) },
        title = stringResource(R.string.lostIconsRestoreTitle),
        body = pluralStringResource(R.plurals.lostIconsRestoreText, restorable, restorable, profileName).withBoldMarkers(),
        actions = {
            DialogPrimaryAction(stringResource(R.string.lostIconsRestore), onRestore)
            if (refreshed > 0) DialogSecondaryAction(stringResource(R.string.lostUnsavedRefresh), onRefresh)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryLater), onLater)
            DialogSecondaryAction(stringResource(R.string.editorRecoveryDiscard), { confirmDiscard = true }, danger = true)
        }
    ) {
        if (dropped > 0 || refreshed > 0) {
            DialogCard {
                if (dropped > 0) {
                    DialogCardNote(stringResource(
                        R.string.lostIconsDroppedNote,
                        pluralStringResource(R.plurals.lostUnsavedEditedCount, dropped, dropped)
                    ))
                }
                if (refreshed > 0) DialogCardNote(pluralStringResource(R.plurals.lostIconsRefreshedNote, refreshed, refreshed))
            }
        }
    }
}
