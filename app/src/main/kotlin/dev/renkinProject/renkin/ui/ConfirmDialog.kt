package dev.renkinProject.renkin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R

/**
 * Shared confirmation dialog for destructive actions (delete / clear). Gives every such
 * prompt one look — it delegates to [RenkinAlertDialog] (the app-wide dialog chrome) and
 * adds an error-tinted confirm, a neutral dismiss and a confirm haptic. Callers supply the
 * texts and what to do on confirm, instead of hand-rolling an AlertDialog each time.
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
    RenkinAlertDialog(
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
