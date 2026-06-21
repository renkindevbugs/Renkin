package dev.alembiconsProject.alembicons.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.alembiconsProject.alembicons.util.Log
import java.util.concurrent.TimeUnit

/**
 * Runs the icon-watch check off the main thread. Triggered two ways:
 *  - a daily [schedulePeriodic] job — the reliable safety net (survives reboot,
 *    battery-friendly, and cheap because [WatchChecker] is version-gated), and
 *  - an immediate [runNow] enqueued by [PackageAddedReceiver] when a pack is replaced.
 *
 * Each fired suggestion posts an "icon available" notification and updates the DB
 * (a completed rule + bell badge).
 */
class WatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val fired = WatchChecker(applicationContext).runCheck()
            Log.debug("Alembicons", "WatchChecker fired ${fired.size} suggestion(s)")
            val notifier = RenkinNotifications()
            for (suggestion in fired) {
                notifier.postIconAvailable(
                    applicationContext,
                    suggestion.suggestionId,
                    suggestion.packageName,
                    suggestion.packPackages
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.debug("Alembicons", "WatchChecker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "icon_watch_periodic"
        private const val ONESHOT_NAME = "icon_watch_oneshot"

        /** Daily safety-net check; only enqueues once (KEEP) so repeated calls are no-ops. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Fast-path check right after a pack was replaced. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
