package dev.renkinProject.renkin.feature.backup

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.renkinProject.renkin.BackupExportFeedback
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.SettingsRow

@Composable
internal fun BackupExportRow(
    feedback: BackupExportFeedback?,
    busy: Boolean,
    onClick: () -> Unit
) {
    val result = feedback as? BackupExportFeedback.Result
    val failed = result?.successful == false
    val label = stringResource(
        if (result?.successful == true) R.string.backupExported else R.string.exportBackup
    )
    val subtitle = when {
        feedback == BackupExportFeedback.Writing -> stringResource(R.string.inlineBackupExportWriting)
        result?.successful == true -> stringResource(R.string.inlineBackupExportSaved)
        result != null -> stringResource(R.string.inlineBackupExportRetry, stringResource(result.messageRes))
        else -> null
    }
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        SettingsRow(
            icon = when {
                failed -> Icons.Filled.ErrorOutline
                result?.successful == true -> Icons.Filled.CheckCircle
                else -> Icons.Filled.Save
            },
            label = label,
            subtitle = subtitle,
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            busy = busy || feedback == BackupExportFeedback.Writing,
            onClick = onClick
        )
    }
}
