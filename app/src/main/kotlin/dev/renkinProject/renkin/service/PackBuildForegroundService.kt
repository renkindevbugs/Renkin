package dev.renkinProject.renkin.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.renkinProject.renkin.BuildInstallSession
import dev.renkinProject.renkin.BuildInstallState
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PackBuildForegroundService : Service() {
    @Inject lateinit var session: BuildInstallSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observer: Job? = null
    private var workId = -1L
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        workId = session.workId
        try {
            ServiceCompat.startForeground(this, BuildProgressNotifications.ONGOING_ID,
                BuildProgressNotifications.ongoing(this, session.state),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            foregroundStarted = true
        } catch (error: RuntimeException) {
            Log.error("PackBuildService", "Could not enter foreground", error)
            session.cancelForegroundWork(workId)
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:pack-build").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        workId = intent?.getLongExtra(EXTRA_WORK_ID, -1L) ?: -1L
        try {
            ServiceCompat.startForeground(this, BuildProgressNotifications.ONGOING_ID,
                BuildProgressNotifications.ongoing(this, session.state),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (error: RuntimeException) {
            Log.error("PackBuildService", "Could not retain foreground", error)
            session.cancelForegroundWork(workId)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val owner = workId
        observer?.cancel()
        observer = scope.launch {
            while (owner == session.workId) {
                val state = session.state
                if (session.hasRunningWork || state is BuildInstallState.Building || state is BuildInstallState.Installing) {
                    try {
                        NotificationManagerCompat.from(this@PackBuildForegroundService).notify(
                            BuildProgressNotifications.ONGOING_ID,
                            BuildProgressNotifications.ongoing(this@PackBuildForegroundService, state)
                        )
                    } catch (_: SecurityException) { }
                } else {
                    BuildProgressNotifications.ready(this@PackBuildForegroundService, state)
                    if (state is BuildInstallState.Failed) BuildProgressNotifications.result(this@PackBuildForegroundService, state.summary)
                    break
                }
                delay(500)
            }
            if (stopSelfResult(startId)) {
                ServiceCompat.stopForeground(this@PackBuildForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        session.cancelForegroundWork(workId)
        stopSelf()
    }

    override fun onDestroy() {
        session.cancelForegroundWork(workId)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_WORK_ID = "pack_build_work_id"
    }
}
