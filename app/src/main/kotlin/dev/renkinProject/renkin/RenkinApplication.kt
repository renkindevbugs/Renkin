package dev.renkinProject.renkin

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.HiltAndroidApp
import dev.renkinProject.renkin.data.transfer.BackupManagerEntryPoint
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.ProfileStyleAccess
import dev.renkinProject.renkin.service.BackgroundWorkScheduler
import dagger.hilt.android.EntryPointAccessors
import dev.renkinProject.renkin.util.CrashReporter
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point for Hilt. [HiltAndroidApp] triggers Hilt's code generation and
 * creates the app-level dependency container that the rest of the app injects from.
 */
@HiltAndroidApp
class RenkinApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (isMainProcess()) {
            ProfileStyleAccess.install {
                EntryPointAccessors.fromApplication(this, BackupManagerEntryPoint::class.java).profileStyleStore()
            }
            // WorkManager's provider starts before onCreate. Install the barrier before any
            // provider can launch a worker that touches persisted state.
            StorageOperationGate.installRecovery {
                EntryPointAccessors.fromApplication(this, BackupManagerEntryPoint::class.java)
                    .backupManager().recoverPendingRestore()
                ProfileStyleAccess.initialize()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a local file so the next launch can offer to email
        // the log (see CrashReporter / the crash dialog in MainActivity).
        CrashReporter.install(this)
        registerCrashLogsShortcut()
        if (isMainProcess()) {
            applicationScope.launch {
                try {
                    StorageOperationGate.run { }
                    BackgroundWorkScheduler.reconcile(this@RenkinApplication)
                } catch (error: Exception) {
                    Log.error("RenkinApplication", "Could not finish recovery and startup maintenance", error)
                }
            }
        }
    }

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return processName == packageName
    }

    /**
     * Long-press the launcher icon → the crash logs. Registered here rather than as a static
     * `<shortcut>` resource because those need the package name spelled out, and debug builds
     * install under a different one. Shortcuts persist, so the entry stays available on the very
     * launch that fails — which is exactly when it is needed.
     */
    private fun registerCrashLogsShortcut() {
        runCatching {
            val intent = Intent(this, CrashLogsActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
            val shortcut = ShortcutInfoCompat.Builder(this, "crashLogs")
                .setShortLabel(getString(R.string.crashLogsShortcutShort))
                .setLongLabel(getString(R.string.crashLogsShortcutLong))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
    }
}
