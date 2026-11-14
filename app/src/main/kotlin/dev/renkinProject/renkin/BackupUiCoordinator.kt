package dev.renkinProject.renkin

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.transfer.AutoBackupManager
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.transfer.BackupMemoryException
import dev.renkinProject.renkin.data.transfer.BackupSizeException
import dev.renkinProject.renkin.data.transfer.BackupSpaceException
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BackupImportPreview(val preview: BackupManager.BackupPreview)

sealed interface BackupExportFeedback {
    data object Writing : BackupExportFeedback
    data class Result(@get:StringRes val messageRes: Int, val successful: Boolean) : BackupExportFeedback
}

internal class BackupUiCoordinator(
    private val provider: ApplicationProvider,
    private val backupManager: BackupManager,
    private val autoBackupManager: AutoBackupManager,
    private val scope: CoroutineScope,
    private val profileSessionOperations: Mutex,
    private val saveBeforeExport: suspend (ProfileSessionToken, Long?) -> Unit,
    private val onRestored: suspend (resetMissingPackPrompt: Boolean) -> Unit,
    private val onVerdictsUpdated: suspend (BackupManager.ImportKind) -> Unit,
    private val notify: (Int) -> Unit
) {
    private data class PendingImport(
        val prepared: BackupManager.PreparedImport,
        val presentation: BackupImportPreview
    )

    private var pendingImport by mutableStateOf<PendingImport?>(null)
    val pendingBackupImport: BackupImportPreview? get() = pendingImport?.presentation
    private var backupJob: Job? = null
    private var closed = false
    var backupInProgress by mutableStateOf(false)
        private set
    var backupCancellationRequested by mutableStateOf(false)
        private set
    var backupExportFeedback by mutableStateOf<BackupExportFeedback?>(null)
        private set

    fun clearBackupExportFeedback() {
        if (!backupInProgress) backupExportFeedback = null
    }

    val previousStateAvailable = backupManager.previousStateAvailable
    val previousStateStored = backupManager.previousStateStored
    val abandonedRecoveryAvailable = backupManager.abandonedRecoveryAvailable
    val backupRestoreStage = backupManager.restoreStage
    val backupSnapshotStage = backupManager.snapshotStage
    val backupRestoreCancellable = backupManager.restoreCancellable
    val autoBackupIntervalHours = autoBackupManager.intervalHours.stateIn(scope, SharingStarted.Eagerly, 0)
    val autoBackupTreeUri = autoBackupManager.treeUri.stateIn(scope, SharingStarted.Eagerly, null)
    val lastAutoBackupAt = autoBackupManager.lastBackupAt.stateIn(scope, SharingStarted.Eagerly, 0L)
    val lastAutoBackupFailedAt = autoBackupManager.lastFailureAt.stateIn(scope, SharingStarted.Eagerly, 0L)

    fun setAutoBackupInterval(hours: Int) = updateAutoBackup("Could not update automatic backup interval") {
        autoBackupManager.setInterval(hours)
    }

    fun setAutoBackupFolder(uri: Uri, intervalHours: Int) = updateAutoBackup("Could not access automatic backup folder") {
        autoBackupManager.setFolder(uri, intervalHours)
    }

    private fun updateAutoBackup(failure: String, update: suspend () -> Unit) {
        if (closed) return
        scope.launch {
            try {
                update()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("BackupUiCoordinator", failure, error)
                notify(R.string.autoBackupSetupFailed)
            }
        }
    }

    suspend fun autoBackupFolderName(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            autoBackupManager.folderName(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun cancelBackupPreparation() {
        val job = backupJob ?: return
        if (backupManager.requestRestorePreparationCancellation()) {
            backupCancellationRequested = true
            job.cancel(CancellationException("Restore preparation cancelled by user"))
        }
    }

    fun discardPreviousState() = runBackupOp(R.string.previousStateDiscardFailed) {
        backupManager.discardPreviousState()
        notify(R.string.previousStateDiscarded)
    }

    fun discardAbandonedRecovery() = runBackupOp(R.string.abandonedRecoveryDiscardFailed) {
        backupManager.discardAbandonedRecovery()
        notify(R.string.abandonedRecoveryDiscarded)
    }

    fun restorePreviousState() = runBackupOp(R.string.previousStateRestoreFailed) {
        backupManager.restorePreviousState { restore -> provider.replaceProfileSession { restore() } }
        onRestored(false)
        notify(R.string.previousStateRestored)
    }

    private fun runBackupOp(
        @StringRes failureToast: Int,
        onCompletion: () -> Unit = {},
        serializeProfile: Boolean = true,
        tracksBackupExport: Boolean = false,
        op: suspend (ProfileSessionToken) -> Unit
    ) {
        if (closed || backupInProgress) return
        backupInProgress = true
        backupExportFeedback = if (tracksBackupExport) BackupExportFeedback.Writing else null
        val expected = provider.sessionToken
        backupJob = scope.launch {
            try {
                if (serializeProfile) profileSessionOperations.withLock { op(expected) }
                else op(expected)
            } catch (error: CancellationException) {
                if (tracksBackupExport) backupExportFeedback = null
                if (backupCancellationRequested) notify(R.string.backupPreparationCancelled)
                throw error
            } catch (error: ProfileSessionChangedException) {
                val message = error.userMessage()
                if (tracksBackupExport) backupExportFeedback = BackupExportFeedback.Result(message, successful = false)
                notify(message)
            } catch (error: Exception) {
                Log.error("BackupUiCoordinator", "Backup operation failed", error)
                val message = when (error) {
                    is BackupSpaceException -> if (failureToast == R.string.backupImportFailed) {
                        R.string.backupImportInsufficientStorage
                    } else R.string.backupInsufficientStorage
                    is BackupSizeException -> R.string.backupSizeLimitExceeded
                    is BackupMemoryException -> R.string.backupInsufficientMemory
                    else -> failureToast
                }
                if (tracksBackupExport) backupExportFeedback = BackupExportFeedback.Result(message, successful = false)
                notify(message)
            } finally {
                backupJob = null
                backupCancellationRequested = false
                backupInProgress = false
            }
        }.also { job -> job.invokeOnCompletion { onCompletion() } }
    }

    fun exportBackup(uri: Uri) = export(uri, profileId = null)

    fun exportProfile(profileId: Long, uri: Uri) = export(uri, profileId)

    private fun export(uri: Uri, profileId: Long?) = runBackupOp(
        if (profileId == null) R.string.backupExportFailed else R.string.profileExportFailed,
        serializeProfile = false,
        tracksBackupExport = profileId == null
    ) { expected ->
        var prepared: BackupManager.PreparedExport? = null
        try {
            profileSessionOperations.withLock {
                saveBeforeExport(expected, profileId)
                prepared = if (profileId == null) backupManager.prepareBackupExport()
                    else backupManager.prepareProfileExport(profileId)
            }
            // The immutable archive owns the snapshot; SAF copying must not hold the profile mutex.
            backupManager.writePreparedExport(checkNotNull(prepared), uri)
        } finally { prepared?.let { backupManager.discardExport(it) } }
        currentCoroutineContext().ensureActive()
        if (profileId == null) {
            backupExportFeedback = BackupExportFeedback.Result(R.string.backupExported, successful = true)
        }
        notify(if (profileId == null) R.string.backupExported else R.string.profileExported)
    }

    fun confirmBackupImport() {
        if (closed || backupInProgress) return
        val pending = pendingImport ?: return
        pendingImport = null
        runBackupOp(R.string.backupImportFailed, onCompletion = { releasePreparedImport(pending.prepared) }) {
            performImport(pending.prepared)
        }
    }

    fun cancelBackupImport() {
        val pending = pendingImport
        pendingImport = null
        pending?.let { releasePreparedImport(it.prepared) }
    }

    private fun releasePreparedImport(prepared: BackupManager.PreparedImport) {
        scope.launch(NonCancellable + Dispatchers.IO) { backupManager.discardImport(prepared) }
    }

    fun importFile(uri: Uri) = runBackupOp(R.string.backupImportFailed) {
        cancelBackupImport()
        val prepared = backupManager.prepareImport(uri)
        var handedOff = false
        try {
            currentCoroutineContext().ensureActive()
            if (closed) return@runBackupOp
            when (prepared.inspection.kind) {
                BackupManager.ImportKind.BACKUP -> {
                    pendingImport = PendingImport(prepared, BackupImportPreview(checkNotNull(prepared.inspection.backup)))
                    handedOff = true
                }
                BackupManager.ImportKind.PROFILE -> performImport(prepared)
            }
        } finally {
            if (!handedOff) backupManager.discardImport(prepared)
        }
    }

    private suspend fun performImport(prepared: BackupManager.PreparedImport) {
        val result = backupManager.importPrepared(prepared) { commit ->
            if (prepared.inspection.kind == BackupManager.ImportKind.BACKUP) {
                provider.replaceProfileSession { commit() }
            } else commit()
        }
        when (result.kind) {
            BackupManager.ImportKind.BACKUP -> {
                onRestored(true)
                notify(R.string.backupImported)
            }
            BackupManager.ImportKind.PROFILE -> notify(R.string.profileImported)
        }
        scope.launch { verifyImportedVerdicts(result.kind) }
    }

    // Quiet best effort like the startup check: store lookups run after the import has released the
    // overlay and the profile lock; only a decisive verdict takes the lock again for its reload.
    private suspend fun verifyImportedVerdicts(kind: BackupManager.ImportKind) {
        try {
            if (provider.verifyPendingVerdicts()) profileSessionOperations.withLock {
                if (!closed) onVerdictsUpdated(kind)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("BackupUiCoordinator", "Could not refresh imported pack verdicts", error)
        }
    }

    fun close() {
        closed = true
        cancelBackupImport()
    }
}
