package dev.renkinProject.renkin.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.RenkinAlertDialog
import dev.renkinProject.renkin.ui.boldStringResource
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.ui.theme.CardShape
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupRestorePreviewDialog(
    preview: BackupManager.BackupPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val backupDate = remember(preview.exportedAt) {
        preview.exportedAt.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
    }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.importBackupTitle)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (backupDate != null || preview.appVersion.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        backupDate?.let {
                            Text(
                                stringResource(R.string.backupPreviewFrom, it),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        if (preview.appVersion.isNotBlank()) {
                            Text(
                                stringResource(R.string.backupPreviewAppVersion, preview.appVersion),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PreviewValueRow(stringResource(R.string.backupPreviewProfiles), preview.profiles.size.toString())
                        PreviewValueRow(stringResource(R.string.backupPreviewIcons), preview.iconCount.toString())
                        PreviewValueRow(
                            stringResource(R.string.backupPreviewWatchRules),
                            (preview.activeWatchRules + preview.completedWatchRules).toString()
                        )
                        Text(
                            stringResource(
                                R.string.backupPreviewWatchBreakdown,
                                preview.activeWatchRules,
                                preview.completedWatchRules
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PreviewValueRow(stringResource(R.string.backupPreviewSavedStyles), preview.savedStyles.toString())
                        PreviewValueRow(
                            stringResource(R.string.backupPreviewUploadedImages),
                            preview.uploadedImages.toString()
                        )

                        if (preview.profiles.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            preview.profiles.take(MAX_VISIBLE_PROFILES).forEach { profile ->
                                PreviewValueRow(
                                    profile.name,
                                    pluralStringResource(
                                        R.plurals.backupPreviewProfileIconCount,
                                        profile.iconCount,
                                        profile.iconCount
                                    )
                                )
                            }
                            val remaining = preview.profiles.size - MAX_VISIBLE_PROFILES
                            if (remaining > 0) {
                                Text(
                                    stringResource(R.string.backupPreviewMoreProfiles, remaining),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Text(
                    boldStringResource(R.string.importBackupText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backupRestoreAction), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}

@Composable
private fun PreviewValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private const val MAX_VISIBLE_PROFILES = 4
