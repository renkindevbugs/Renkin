package dev.renkinProject.renkin.service

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchWorkerScheduleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var now = 1_800_000_000_000L
    private lateinit var database: WorkDatabase
    private lateinit var workManager: WorkManagerImpl

    @Before fun setUp() {
        val directExecutor = Executor { it.run() }
        val configuration = Configuration.Builder()
            .setExecutor(directExecutor)
            .setTaskExecutor(directExecutor)
            .setClock { now }
            .build()
        database = WorkDatabase.create(context, directExecutor, configuration.clock, true)
        // Keep WorkManager's real persistence/policies while preventing workers from executing.
        workManager = WorkManagerImpl(
            context, configuration, workDatabase = database,
            schedulersCreator = { _, _, _, _, _, _ -> emptyList() }
        )
        WorkManagerImpl.setDelegate(workManager)
    }

    @After fun tearDown() {
        WorkManagerImpl.setDelegate(null)
        database.close()
    }

    private fun periodic(): WorkInfo = workManager
        .getWorkInfosForUniqueWork("icon_watch_periodic").get(5, TimeUnit.SECONDS)
        .single { it.state == WorkInfo.State.ENQUEUED }

    @Test fun firstPeriodicCheckWaitsOneIntervalAndKeepsTheRequestedCadence() {
        WatchWorker.schedulePeriodic(context, intervalMinutes = 24 * 60)

        val pending = periodic()
        val day = TimeUnit.DAYS.toMillis(1)
        assertEquals(day, pending.initialDelayMillis)
        assertEquals(day, pending.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(now + day, pending.nextScheduleTimeMillis)
        assertTrue(pending.constraints.requiresBatteryNotLow())
    }

    @Test fun restartingAfterManualCheckAndStartupUpdateKeepTheNextCheckInTheFuture() {
        WatchWorker.schedulePeriodic(context, intervalMinutes = 60)
        val original = periodic()
        now += TimeUnit.MINUTES.toMillis(5)

        WatchWorker.schedulePeriodic(
            context, intervalMinutes = 60, policy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        )

        val restarted = periodic()
        val expectedNextCheck = now + TimeUnit.HOURS.toMillis(1)
        assertNotEquals(original.id, restarted.id)
        assertEquals(expectedNextCheck, restarted.nextScheduleTimeMillis)
        assertTrue(restarted.nextScheduleTimeMillis > now)

        now += TimeUnit.MINUTES.toMillis(1)
        WatchWorker.schedulePeriodic(context, intervalMinutes = 60, policy = ExistingPeriodicWorkPolicy.UPDATE)

        val afterStartup = periodic()
        assertEquals(restarted.id, afterStartup.id)
        assertEquals(expectedNextCheck, afterStartup.nextScheduleTimeMillis)
        assertTrue(afterStartup.nextScheduleTimeMillis > now)
    }

    @Test fun disablingAutomaticChecksCancelsPeriodicAndImmediateWork() {
        WatchWorker.schedulePeriodic(context, intervalMinutes = 60)
        WatchWorker.runNow(context)
        val immediate = workManager.getWorkInfosForUniqueWork("icon_watch_oneshot")
            .get(5, TimeUnit.SECONDS).single()
        assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
        assertEquals(0L, immediate.initialDelayMillis)
        assertEquals(now, immediate.nextScheduleTimeMillis)

        WatchWorker.schedulePeriodic(context, enabled = false)

        val periodicWork = workManager.getWorkInfosForUniqueWork("icon_watch_periodic").get(5, TimeUnit.SECONDS)
        val immediateWork = workManager.getWorkInfosForUniqueWork("icon_watch_oneshot").get(5, TimeUnit.SECONDS)
        assertTrue(periodicWork.isNotEmpty())
        assertTrue(periodicWork.all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(listOf(WorkInfo.State.CANCELLED), immediateWork.map { it.state })
    }
}
