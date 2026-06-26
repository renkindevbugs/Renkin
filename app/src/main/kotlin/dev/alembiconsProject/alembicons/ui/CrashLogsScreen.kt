package dev.alembiconsProject.alembicons.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.ui.theme.CardShape
import dev.alembiconsProject.alembicons.ui.theme.DialogShape
import dev.alembiconsProject.alembicons.ui.theme.InnerShape
import dev.alembiconsProject.alembicons.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen list of captured crash logs (newest first). Each entry opens a detail sheet to
 * read, copy, share or delete the full trace; the top bar clears them all. Logs are kept for
 * 30 days by [CrashReporter]; this screen only reads and prunes on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogsScreen(onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val toaster = LocalToaster.current
    val clearedMessage = stringResource(R.string.crashLogsCleared)
    val deletedMessage = stringResource(R.string.crashLogDeleted)

    // Bumped after a delete / clear to reload the list off the main thread.
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<CrashReporter.CrashEntry>>(emptyList()) }
    LaunchedEffect(reloadTrigger) {
        entries = withContext(Dispatchers.Default) { CrashReporter.list(context) }
    }

    var selected by remember { mutableStateOf<CrashReporter.CrashEntry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.crashLogs)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close))
                            }
                        },
                        actions = {
                            if (entries.isNotEmpty()) {
                                IconButton(onClick = { confirmClearAll = true }) {
                                    Icon(
                                        Icons.Filled.DeleteSweep,
                                        stringResource(R.string.crashLogsClearAll),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            ) { innerPadding ->
                if (entries.isEmpty()) {
                    EmptyState(Modifier.padding(innerPadding))
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            GithubReportLink()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.crashLogsSubtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            CrashLogCard(entry) { selected = entry }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        CrashLogDetailDialog(
            entry = entry,
            onDismiss = { selected = null },
            onDelete = {
                CrashReporter.delete(context, entry.id)
                selected = null
                reloadTrigger++
                toaster.show(deletedMessage)
            }
        )
    }

    if (confirmClearAll) {
        ConfirmDialog(
            title = stringResource(R.string.crashLogsClearAllTitle),
            text = stringResource(R.string.crashLogsClearAllText),
            onConfirm = {
                CrashReporter.clearAll(context)
                confirmClearAll = false
                reloadTrigger++
                toaster.show(clearedMessage)
            },
            onDismiss = { confirmClearAll = false }
        )
    }
}

/** Prominent, centered link to the GitHub issues page so a stored crash can be reported. */
@Composable
private fun GithubReportLink() {
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.crashGithubUrl)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { uriHandler.openUri(url) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_github),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.crashLogsReport),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.crashLogsEmpty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CrashLogCard(entry: CrashReporter.CrashEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = formatTimestamp(entry.timestamp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = summaryLine(entry.text),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CrashLogDetailDialog(
    entry: CrashReporter.CrashEntry,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val context = getCurrentContext()
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedMessage = stringResource(R.string.crashLogCopied)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                // The trace can be long — scroll it inside a bounded, code-block surface.
                Surface(
                    shape = InnerShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(entry.text))
                        toaster.show(copiedMessage)
                    }) {
                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.crashCopyLog))
                    }
                    IconButton(onClick = { shareCrash(context, entry.text) }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.crashLogShare))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.crashLogDelete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

/** Opens the system share sheet with the crash text. */
private fun shareCrash(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Renkin crash log")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

private fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestamp))

/** The first stack line (exception type + message), used as a one-glance summary. */
private fun summaryLine(text: String): String =
    text.substringAfter("\n\n", text).trim().lineSequence().firstOrNull().orEmpty()
