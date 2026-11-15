package dev.renkinProject.renkin

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.compose.runtime.snapshotFlow
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.BuiltIconPack
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.service.BuildProgressNotifications
import dev.renkinProject.renkin.service.PackBuildForegroundService
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps the build and its APK handoff alive independently of the activity's ViewModel. */
@Singleton
class BuildInstallSession @Inject constructor(
    private val application: Application,
    private val provider: ApplicationProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coordinator: BuildInstallCoordinator? = null
    private var uiOwner: Any? = null
    private var notice: ((Int) -> Unit)? = null
    private var textNotice: ((String) -> Unit)? = null
    private var foreground = false
    internal var workId: Long = 0L
        private set

    internal val state: BuildInstallState get() = coordinator?.state ?: BuildInstallState.Idle
    internal val hasRunningWork: Boolean get() = coordinator?.hasRunningWork == true

    internal fun attach(
        owner: Any,
        sync: suspend (BuiltIconPack) -> Unit,
        refresh: suspend () -> Unit,
        notify: (Int) -> Unit,
        notifyText: (String) -> Unit,
        exportState: PendingApkExportState
    ): BuildInstallCoordinator {
        uiOwner = owner
        notice = notify
        textNotice = notifyText
        val current = coordinator ?: BuildInstallCoordinator(
            application, provider, scope, sync, refresh, ::onNotice, ::onTextNotice, exportState
        ).also { created ->
            coordinator = created
            scope.launch {
                var previous: BuildInstallState = BuildInstallState.Idle
                snapshotFlow { created.state }.collect { next ->
                    if (next is BuildInstallState.Building || next is BuildInstallState.Installing ||
                        (next is BuildInstallState.Done && foreground) ||
                        (next is BuildInstallState.Idle && foreground && previous !is BuildInstallState.Building)) {
                        BuildProgressNotifications.clearResult(application)
                    }
                    previous = next
                }
            }
        }
        current.attach(sync, refresh, ::onNotice, ::onTextNotice, exportState) {
            foreground && uiOwner != null
        }
        return current
    }

    internal fun detach(owner: Any) {
        if (uiOwner !== owner) return
        uiOwner = null
        notice = null
        textNotice = null
        coordinator?.detach()
    }

    internal fun startBuild(expected: ProfileSessionToken) {
        val current = coordinator ?: return
        if (current.busy) return
        continueWork { current.build(expected) }
    }

    internal fun continueWork(start: () -> Unit) {
        workId++
        try {
            ContextCompat.startForegroundService(application, Intent(application, PackBuildForegroundService::class.java)
                .putExtra(PackBuildForegroundService.EXTRA_WORK_ID, workId))
        } catch (error: RuntimeException) {
            Log.error("BuildInstallSession", "Could not start foreground build", error)
            onNotice(R.string.buildForegroundUnavailable)
            return
        }
        start()
    }

    internal fun setForeground(value: Boolean) { foreground = value }

    internal fun cancelForegroundWork(expectedWorkId: Long) {
        if (expectedWorkId == workId) coordinator?.cancelForegroundWork()
    }

    private fun onNotice(message: Int) {
        notice?.invoke(message)
        if (!foreground) BuildProgressNotifications.result(application, application.getString(message))
    }

    private fun onTextNotice(message: String) {
        textNotice?.invoke(message)
        if (!foreground) BuildProgressNotifications.result(application, message)
    }
}
