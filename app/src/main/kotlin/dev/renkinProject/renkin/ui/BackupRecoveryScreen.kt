package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.StorageRecoveryFailureReason
import dev.renkinProject.renkin.data.transfer.BackupManager

@Composable
fun BackupRecoveryScreen(
    state: StorageRecoveryState,
    failureReason: StorageRecoveryFailureReason?,
    actionError: Int?,
    busy: Boolean,
    onRetry: () -> Unit,
    onKeepCurrent: () -> Unit
) {
    var confirmKeepCurrent by rememberSaveable { mutableStateOf(false) }
    val keepCurrentRecommended = failureReason == StorageRecoveryFailureReason.SCHEMA_CHANGED ||
        failureReason == StorageRecoveryFailureReason.CORRUPT_SNAPSHOT
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(if (state == StorageRecoveryState.CHECKING) R.string.backupRecoveryCheckingTitle else R.string.backupRecoveryTitle),
                style = MaterialTheme.typography.headlineSmall
            )
            if (state == StorageRecoveryState.FAILED) {
                Text(stringResource(when (failureReason) {
                    StorageRecoveryFailureReason.SCHEMA_CHANGED -> R.string.backupRecoverySchemaChanged
                    StorageRecoveryFailureReason.CORRUPT_SNAPSHOT -> R.string.backupRecoverySnapshotCorrupt
                    StorageRecoveryFailureReason.IO -> R.string.backupRecoveryIoFailed
                    else -> R.string.backupRecoveryFailed
                }))
                actionError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (keepCurrentRecommended) {
                    Button(onClick = { confirmKeepCurrent = true }, enabled = !busy) {
                        Text(stringResource(R.string.backupRecoveryKeepCurrent))
                    }
                    TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.backupRecoveryRetry)) }
                } else {
                    Button(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.backupRecoveryRetry)) }
                    TextButton(onClick = { confirmKeepCurrent = true }, enabled = !busy) {
                        Text(stringResource(R.string.backupRecoveryKeepCurrent))
                    }
                }
            } else {
                CircularProgressIndicator()
                Text(stringResource(if (state == StorageRecoveryState.CHECKING) R.string.backupRecoveryChecking else R.string.backupRecoveryRunning))
            }
        }
    }
    if (confirmKeepCurrent && state == StorageRecoveryState.FAILED) {
        ConfirmDialog(
            title = stringResource(R.string.backupRecoveryKeepCurrent),
            text = stringResource(R.string.backupRecoveryKeepCurrentConfirmation),
            onConfirm = { confirmKeepCurrent = false; onKeepCurrent() },
            onDismiss = { confirmKeepCurrent = false }
        )
    }
}

@Composable
fun BackupRestoreProgress(stage: BackupManager.RestoreStage) {
    if (stage == BackupManager.RestoreStage.IDLE) return
    val message = when (stage) {
        BackupManager.RestoreStage.VALIDATING_SAVED_STATE -> R.string.backupValidatingSavedState
        BackupManager.RestoreStage.SAVING_CURRENT_STATE -> R.string.backupSavingCurrentState
        BackupManager.RestoreStage.RESTORING_DATA -> R.string.backupRestoringData
        BackupManager.RestoreStage.RESTORING_FILES -> R.string.backupRestoringFiles
        BackupManager.RestoreStage.IDLE -> return
    }
    RenkinAlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(message)) },
        text = { CircularProgressIndicator() }
    )
}
