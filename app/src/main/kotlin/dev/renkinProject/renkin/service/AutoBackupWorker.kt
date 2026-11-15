package dev.renkinProject.renkin.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dev.renkinProject.renkin.data.AUTO_BACKUP_INTERVAL_OFF
import dev.renkinProject.renkin.data.AutoBackupTreeUriKey
import dev.renkinProject.renkin.data.LastAutoBackupAtKey
import dev.renkinProject.renkin.data.LastAutoBackupDigestKey
import dev.renkinProject.renkin.data.LastAutoBackupDocumentUriKey
import dev.renkinProject.renkin.data.LastAutoBackupFailedAtKey
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.normalizeAutoBackupInterval
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.transfer.BackupManagerEntryPoint
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class AutoBackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = backupRuns.withLock { performBackup() }

    private suspend fun performBackup(): Result {
        var outputUri: Uri? = null
        return try {
            StorageOperationGate.run { }
            val prefs = applicationContext.dataStore.getPreferencesAfterPendingWrites()
            val treeUri = prefs.getStringValue(AutoBackupTreeUriKey)
                .takeIf(String::isNotEmpty)
                ?.let(Uri::parse)
                ?: return Result.failure()
            val backupManager = EntryPointAccessors.fromApplication(applicationContext, BackupManagerEntryPoint::class.java)
                .backupManager()
            val prepared = backupManager.prepareBackupExport()
            try {
                val digest = backupManager.contentDigest(prepared)
                val previousDocument = prefs.getStringValue(LastAutoBackupDocumentUriKey)
                val unchanged = digest == prefs.getStringValue(LastAutoBackupDigestKey) &&
                    previousDocument.isNotEmpty() &&
                    existingBackups(treeUri).any { it.first.toString() == previousDocument }
                if (!unchanged) {
                    backupManager.writePreparedExport(prepared) {
                        val createdUri = createBackupDocument(treeUri)
                        outputUri = createdUri
                        applicationContext.contentResolver.openOutputStream(createdUri)
                            ?: throw IOException("Cannot open automatic backup for writing")
                    }
                }
                // A completed backup must survive cancellation, a failed bookkeeping write or a
                // failed retention scan, so it stops being cleanup-eligible as soon as it is written.
                val completedUri = outputUri
                outputUri = null
                StorageOperationGate.run {
                    applicationContext.dataStore.edit { current ->
                        if (current[AutoBackupTreeUriKey] == treeUri.toString()) {
                            current[LastAutoBackupDigestKey] = digest
                            current[LastAutoBackupDocumentUriKey] = completedUri?.toString() ?: previousDocument
                            current[LastAutoBackupAtKey] = System.currentTimeMillis()
                            current.remove(LastAutoBackupFailedAtKey)
                        }
                    }
                }
                if (!unchanged) {
                    try {
                        deleteExpiredBackups(treeUri)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.error("AutoBackupWorker", "Could not prune old backups", error)
                    }
                }
            } finally {
                backupManager.discardExport(prepared)
            }
            Result.success()
        } catch (e: CancellationException) {
            outputUri?.let(::deleteDocument)
            throw e
        } catch (e: SecurityException) {
            outputUri?.let(::deleteDocument)
            Log.error("AutoBackupWorker", "Automatic backup folder is no longer accessible", e)
            recordFailure()
            Result.failure()
        } catch (e: Exception) {
            outputUri?.let(::deleteDocument)
            Log.error("AutoBackupWorker", "Automatic backup failed", e)
            recordFailure()
            Result.retry()
        }
    }

    // Settings otherwise kept showing the last successful date while no backups were made.
    private suspend fun recordFailure() {
        try {
            StorageOperationGate.run {
                applicationContext.dataStore.edit { it[LastAutoBackupFailedAtKey] = System.currentTimeMillis() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("AutoBackupWorker", "Could not record the failed backup", error)
        }
    }

    private fun createBackupDocument(treeUri: Uri): Uri {
        val resolver = applicationContext.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val name = "renkin-auto-backup-${FILE_TIMESTAMP.format(LocalDateTime.now())}.renkin"
        return DocumentsContract.createDocument(resolver, root, BACKUP_MIME_TYPE, name)
            ?: throw IOException("Cannot create automatic backup in $treeUri")
    }

    private fun deleteExpiredBackups(treeUri: Uri) {
        existingBackups(treeUri).sortedByDescending { it.second }
            .drop(MAX_BACKUPS)
            .forEach { deleteDocument(it.first) }
    }

    private fun existingBackups(treeUri: Uri): List<Pair<Uri, String>> {
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        return buildList {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
                        add(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex)) to
                                name
                        )
                    }
                }
            }
        }
    }

    private fun deleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
    }

    companion object {
        private val backupRuns = Mutex()
        private const val WORK_NAME = "automatic_backup"
        private const val IMMEDIATE_WORK_NAME = "automatic_backup_now"
        private const val BACKUP_MIME_TYPE = "application/octet-stream"
        private const val FILE_PREFIX = "renkin-auto-backup-"
        private const val FILE_SUFFIX = ".renkin"
        private const val MAX_BACKUPS = 5
        private val FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

        fun schedule(
            context: Context,
            intervalHours: Int,
            policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE
        ) {
            val normalized = normalizeAutoBackupInterval(intervalHours)
            val workManager = WorkManager.getInstance(context)
            if (normalized == AUTO_BACKUP_INTERVAL_OFF) {
                workManager.cancelUniqueWork(WORK_NAME)
                workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                normalized.toLong(),
                TimeUnit.HOURS
            )
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
