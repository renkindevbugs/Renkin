package dev.renkinProject.renkin.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import dev.renkinProject.renkin.data.AUTO_BACKUP_INTERVAL_OFF
import dev.renkinProject.renkin.data.AutoBackupIntervalKey
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.WATCH_CHECK_INTERVAL_DEFAULT
import dev.renkinProject.renkin.data.WatchCheckIntervalKey
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.normalizeAutoBackupInterval
import dev.renkinProject.renkin.data.normalizeWatchCheckInterval
import dev.renkinProject.renkin.dataStore

object BackgroundWorkScheduler {
    suspend fun reconcile(context: Context): Unit = StorageOperationGate.run {
        val prefs = context.dataStore.getPreferencesAfterPendingWrites()
        // UPDATE retains enqueue time and also repairs a cadence whose preference commit
        // completed just before process death, before its WorkManager update was submitted.
        WatchWorker.schedulePeriodic(
            context,
            normalizeWatchCheckInterval(prefs.getIntValue(WatchCheckIntervalKey, WATCH_CHECK_INTERVAL_DEFAULT)),
            ExistingPeriodicWorkPolicy.UPDATE
        )
        AutoBackupWorker.schedule(
            context,
            normalizeAutoBackupInterval(prefs.getIntValue(AutoBackupIntervalKey, AUTO_BACKUP_INTERVAL_OFF)),
            ExistingPeriodicWorkPolicy.UPDATE
        )
    }
}
