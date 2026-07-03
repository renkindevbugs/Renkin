package dev.renkinProject.renkin.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.WATCH_CHECK_INTERVAL_DEFAULT
import dev.renkinProject.renkin.data.getLongValue
import dev.renkinProject.renkin.data.setLongValue
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Runs the icon-watch check off the main thread on a periodic [schedulePeriodic] job — the
 * watch trigger (24h by default, battery-friendly, and cheap because [WatchChecker] is
 * version-gated, so a run does almost nothing unless a pack actually changed). [runNow]
 * enqueues an immediate one-off check, used by the debug "simulate" action.
 *
 * Each fired suggestion posts an "icon available" notification and updates the DB
 * (a completed rule + bell badge).
 *
 * The periodic run also enforces a minimum spacing against the last check using the wall
 * clock, so changing the device time can't make it effectively check more often than the
 * configured interval. Manual refresh and pack-replace events deliberately bypass that.
 */
class WatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            if (inputData.getBoolean(KEY_PERIODIC, false) && !enoughTimeElapsed()) {
                Log.debug("Alembicons", "WatchChecker periodic run skipped — too soon since last check")
                return Result.success()
            }

            val fired = WatchChecker(applicationContext).runCheck()
            Log.debug("Alembicons", "WatchChecker fired ${fired.size} suggestion(s)")
            val notifier = RenkinNotifications()
            val profileRepo = RenkinPackRepository(applicationContext)
            for (suggestion in fired) {
                // Name the owning profile in the notification; the tap deep-links into it.
                val profileName = profileRepo.profile(suggestion.profileId)?.name ?: "Renkin"
                notifier.postIconAvailable(
                    applicationContext,
                    suggestion.suggestionId,
                    suggestion.packageName,
                    suggestion.packPackages,
                    suggestion.profileId,
                    profileName
                )
            }
            applicationContext.dataStore.setLongValue(LastWatchCheckAtKey, System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Log.debug("Alembicons", "WatchChecker failed: ${e.message}")
            Result.retry()
        }
    }

    /** True if at least ~80% of the configured interval has really elapsed since the last check. */
    private suspend fun enoughTimeElapsed(): Boolean {
        val intervalMinutes = inputData.getInt(KEY_INTERVAL_MINUTES, WATCH_CHECK_INTERVAL_DEFAULT)
        val last = applicationContext.dataStore.data.first().getLongValue(LastWatchCheckAtKey, 0L)
        if (last == 0L) return true
        val elapsed = System.currentTimeMillis() - last
        // Clock moved backwards (manual change / timezone) → don't trust it, allow the run.
        if (elapsed < 0L) return true
        return elapsed >= intervalMinutes.toLong() * 60_000L * 4 / 5
    }

    companion object {
        private const val PERIODIC_NAME = "icon_watch_periodic"
        private const val ONESHOT_NAME = "icon_watch_oneshot"
        private const val KEY_PERIODIC = "periodic"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"

        /**
         * Schedules the periodic safety-net check at [intervalMinutes] (WorkManager's floor
         * is 15 min). Use [ExistingPeriodicWorkPolicy.KEEP] at startup so the running timer
         * isn't reset, and [ExistingPeriodicWorkPolicy.UPDATE] when the user changes the
         * interval so the new cadence applies immediately.
         */
        fun schedulePeriodic(
            context: Context,
            intervalMinutes: Int = WATCH_CHECK_INTERVAL_DEFAULT,
            policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        ) {
            val request = PeriodicWorkRequestBuilder<WatchWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_PERIODIC to true, KEY_INTERVAL_MINUTES to intervalMinutes))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, policy, request)
        }

        /** Enqueues an immediate one-off check (used by the debug "simulate" action). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Epoch-millis of the next scheduled periodic check, or null when none is pending. */
        fun nextScheduledCheckFlow(context: Context): Flow<Long?> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(PERIODIC_NAME)
                .map { infos ->
                    infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                        ?.nextScheduleTimeMillis
                        ?.takeIf { it in 1 until Long.MAX_VALUE }
                }
    }
}
