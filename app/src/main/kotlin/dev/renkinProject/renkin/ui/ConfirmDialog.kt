package dev.renkinProject.renkin.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R

/**
 * Shared confirmation dialog for destructive actions (delete / clear / replace): a danger badge,
 * an error-coloured confirm with a confirm haptic and a neutral dismiss. `**bold**` markers in
 * [text] highlight its load-bearing part. When the action targets one named thing, pass the
 * action as [eyebrow] and the name as [title] so the name leads.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.confirm),
    dismissLabel: String = stringResource(R.string.dismiss),
    icon: ImageVector? = null,
    eyebrow: String? = null,
) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(icon ?: Icons.Filled.Warning, DialogTone.Danger) },
        eyebrow = eyebrow,
        title = title,
        body = text?.withBoldMarkers(),
        actions = {
            DialogPrimaryAction(confirmLabel, onConfirm, DialogTone.Danger)
            DialogSecondaryAction(dismissLabel, onDismiss)
        }
    )
}
