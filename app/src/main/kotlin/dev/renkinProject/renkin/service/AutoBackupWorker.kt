package dev.renkinProject.renkin.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.normalizeAutoBackupInterval
import dev.renkinProject.renkin.data.setLongValue
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.transfer.BackupManagerEntryPoint
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class AutoBackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var outputUri: Uri? = null
        return try {
            StorageOperationGate.run { }
            val treeUri = applicationContext.dataStore.getPreferencesAfterPendingWrites()
                .getStringValue(AutoBackupTreeUriKey)
                .takeIf(String::isNotEmpty)
                ?.let(Uri::parse)
                ?: return Result.failure()
            EntryPointAccessors.fromApplication(applicationContext, BackupManagerEntryPoint::class.java)
                .backupManager().exportBackup {
                    val createdUri = createBackupDocument(treeUri)
                    outputUri = createdUri
                    applicationContext.contentResolver.openOutputStream(createdUri)
                        ?: throw IOException("Cannot open automatic backup for writing")
                }
            runCatching { deleteExpiredBackups(treeUri) }
                .onFailure { Log.error("AutoBackupWorker", "Could not prune old backups", it) }
            applicationContext.dataStore.setLongValue(LastAutoBackupAtKey, System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            outputUri?.let(::deleteDocument)
            throw e
        } catch (e: SecurityException) {
            outputUri?.let(::deleteDocument)
            Log.error("AutoBackupWorker", "Automatic backup folder is no longer accessible", e)
            Result.failure()
        } catch (e: Exception) {
            outputUri?.let(::deleteDocument)
            Log.error("AutoBackupWorker", "Automatic backup failed", e)
            Result.retry()
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
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val documents = buildList {
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
        documents.sortedByDescending { it.second }
            .drop(MAX_BACKUPS)
            .forEach { deleteDocument(it.first) }
    }

    private fun deleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
    }

    companion object {
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
