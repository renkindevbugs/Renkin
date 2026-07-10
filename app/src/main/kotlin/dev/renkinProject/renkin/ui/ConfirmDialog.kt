package dev.renkinProject.renkin.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R

/**
 * Shared confirmation dialog for destructive actions (delete / clear / replace). Gives every
 * such prompt one look — it delegates to [RenkinAlertDialog] (the app-wide dialog chrome) and
 * adds an error-tinted confirm, a neutral dismiss and a confirm haptic. An optional [icon]
 * (error-tinted) shows at a glance what kind of action is being confirmed, and `**bold**`
 * markers in [text] highlight its load-bearing part (see [withBoldMarkers]).
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.confirm),
    dismissLabel: String = stringResource(R.string.dismiss),
    icon: ImageVector? = null,
) {
    val view = LocalView.current
    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let {
            { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        },
        title = { Text(title) },
        text = { Text(text.withBoldMarkers()) },
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
