package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.util.CrashReporter

private const val DEV_EMAIL = "renkin.dev.bugs@gmail.com"

/**
 * Shown once on launch after a crash. Fully offline: it just lets the user copy the log
 * (to email it or paste into a GitHub issue) — nothing is sent automatically, so the app
 * needs no internet permission. [onDismiss] is expected to clear the stored log.
 */
@Composable
fun CrashReportDialog(onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current

    val githubUrl = stringResource(R.string.crashGithubUrl)
    val logCopied = stringResource(R.string.crashLogCopied)
    val emailCopied = stringResource(R.string.crashEmailCopied)

    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crashTitle)) },
        text = {
            Column {
                Text(stringResource(R.string.crashText))
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val log = CrashReporter.readLog(context) ?: return@TextButton
                    clipboard.setText(AnnotatedString(log))
                    toaster.show(logCopied)
                }) { Text(stringResource(R.string.crashCopyLog)) }
                // Tap to copy the address (no email app is launched).
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(DEV_EMAIL))
                    toaster.show(emailCopied)
                }) { Text(DEV_EMAIL) }
                TextButton(onClick = { uriHandler.openUri(githubUrl) }) {
                    Text(stringResource(R.string.crashReportGithub))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
