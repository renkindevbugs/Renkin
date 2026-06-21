package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R

/**
 * Shown once on launch when the previous session crashed: explains the local crash log and
 * offers to email it. [onSend] opens the email chooser; both actions clear the log.
 */
@Composable
fun CrashReportDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crashTitle)) },
        text = { Text(stringResource(R.string.crashText)) },
        confirmButton = {
            TextButton(onClick = onSend) { Text(stringResource(R.string.crashSend)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
