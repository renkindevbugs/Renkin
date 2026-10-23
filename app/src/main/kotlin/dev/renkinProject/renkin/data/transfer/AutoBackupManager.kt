package dev.renkinProject.renkin.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.data.AUTO_BACKUP_INTERVAL_OFF
import dev.renkinProject.renkin.data.AutoBackupIntervalKey
import dev.renkinProject.renkin.data.AutoBackupTreeUriKey
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.LastAutoBackupAtKey
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getLongValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.normalizeAutoBackupInterval
import dev.renkinProject.renkin.data.setIntValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.data.observePreferences
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.service.AutoBackupWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val intervalHours: Flow<Int> = context.dataStore.observePreferences().map {
        normalizeAutoBackupInterval(it.getIntValue(AutoBackupIntervalKey, AUTO_BACKUP_INTERVAL_OFF))
    }

    val treeUri: Flow<Uri?> = context.dataStore.observePreferences().map { preferences ->
        preferences.getStringValue(AutoBackupTreeUriKey)
            .takeIf(String::isNotEmpty)
            ?.let(Uri::parse)
    }

    val lastBackupAt: Flow<Long> = context.dataStore.observePreferences().map {
        it.getLongValue(LastAutoBackupAtKey)
    }

    suspend fun setInterval(hours: Int): Unit = StorageOperationGate.run {
        val normalized = normalizeAutoBackupInterval(hours)
        context.dataStore.setIntValue(AutoBackupIntervalKey, normalized)
        AutoBackupWorker.schedule(context, normalized)
    }

    suspend fun setFolder(uri: Uri, intervalHours: Int): Unit = StorageOperationGate.run {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.dataStore.setStringValue(AutoBackupTreeUriKey, uri.toString())
        setInterval(intervalHours)
        AutoBackupWorker.runNow(context)
    }

    fun folderName(uri: Uri): String? {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri)
        )
        return context.contentResolver.query(
            root,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        }
    }
}
