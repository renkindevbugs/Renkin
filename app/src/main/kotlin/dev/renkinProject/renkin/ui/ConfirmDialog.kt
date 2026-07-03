package dev.renkinProject.renkin.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.DialogShape

/**
 * Shared confirmation dialog for destructive actions (delete / clear). Gives every such
 * prompt one look: [DialogShape] on an elevated surface, an error-tinted confirm and a
 * neutral dismiss, plus a confirm haptic. Callers supply the texts and what to do on
 * confirm, instead of hand-rolling an AlertDialog each time (the five that existed had
 * drifted between icon- and text-button styles).
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.confirm),
    dismissLabel: String = stringResource(R.string.dismiss),
) {
    val view = LocalView.current
    AlertDialog(
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                view.performConfirmHaptic()
                onConfirm()
            }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}
