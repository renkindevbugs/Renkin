package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
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
