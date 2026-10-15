package dev.renkinProject.renkin

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.apk.ApkInstallBackend
import dev.renkinProject.renkin.apk.ApkInstallOutcome
import dev.renkinProject.renkin.apk.ApkInstallResult
import dev.renkinProject.renkin.apk.ApkInstallRoute
import dev.renkinProject.renkin.apk.ApkExportLease
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.BuiltIconPack
import dev.renkinProject.renkin.apk.HomeLauncher
import dev.renkinProject.renkin.apk.InstallFailureHelp
import dev.renkinProject.renkin.apk.InstallerSelection
import dev.renkinProject.renkin.apk.installFailureHelp
import dev.renkinProject.renkin.apk.ProfileOperationBusyException
import dev.renkinProject.renkin.apk.ProfileOperationSupersededException
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.apk.SIGNATURE_MISMATCH_DETAIL
import dev.renkinProject.renkin.apk.installerSelection
import dev.renkinProject.renkin.apk.resolveHomeLauncher
import dev.renkinProject.renkin.data.AskInstallerEveryTimeKey
import dev.renkinProject.renkin.data.ExternalInstallerComponentKey
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.data.InstallMethodKey
import dev.renkinProject.renkin.data.InstallerChoiceConfirmedKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InstallerChoiceReason { FIRST_CHOICE, ASK_EVERY_TIME }

internal fun installerChoiceReason(askEveryTime: Boolean, choiceConfirmed: Boolean): InstallerChoiceReason? = when {
    askEveryTime -> InstallerChoiceReason.ASK_EVERY_TIME
    !choiceConfirmed -> InstallerChoiceReason.FIRST_CHOICE
    else -> null
}

internal data class PackInstallRequest(
    val pack: BuiltIconPack,
    val wasUpdate: Boolean,
    val selection: InstallerSelection
) {
    val failedMessage: Int get() = if (wasUpdate) R.string.iconPackUpdateFailed else R.string.iconPackInstallFailed
}

internal sealed interface BuildInstallState {
    data object Idle : BuildInstallState
    data class Building(val step: String = "", val progress: Pair<Int, Int>? = null) : BuildInstallState
    data class ChoosingInstaller(val request: PackInstallRequest, val reason: InstallerChoiceReason) : BuildInstallState
    data class Installing(val request: PackInstallRequest, val replacing: Boolean = false,
        val route: ApkInstallRoute? = null) : BuildInstallState
    data class Conflict(val request: PackInstallRequest, val signatureMismatch: Boolean = false) : BuildInstallState
    data class Done(val wasUpdate: Boolean, val packLabel: String, val installerLabel: String,
        val savingProfile: Boolean = false, val profileSaveFailed: Boolean = false,
        val launcher: HomeLauncher? = null) : BuildInstallState
    data class Failed(@StringRes val message: Int, val summary: String, val report: String,
        val result: ApkInstallResult? = null, val pack: BuiltIconPack? = null,
        val help: InstallFailureHelp? = null) : BuildInstallState
}

internal class BuildInstallCoordinator(
    private val application: Application,
    private val provider: ApplicationProvider,
    private val scope: CoroutineScope,
    private val syncBaselines: suspend (BuiltIconPack) -> Unit,
    private val refreshMissingPacks: suspend () -> Unit,
    private val notify: (Int) -> Unit,
    private val notifyText: (String) -> Unit,
    private val pendingApkExport: PendingApkExportState
) {
    var state by mutableStateOf<BuildInstallState>(BuildInstallState.Idle)
        private set
    private var operation = 0L
    private var job: Job? = null
    private var closed = false
    private data class InstallCompletionOwner(val operation: Long, val pack: BuiltIconPack)
    private var completingInstallOwner by mutableStateOf<InstallCompletionOwner?>(null)
    // Main-confined cleanup ownership survives the prompt's transition to Idle.
    private val finishingPacks = mutableListOf<BuiltIconPack>()
    private var preparingApkExport = false

    val busy: Boolean get() = completingInstallOwner != null || when (state) {
        is BuildInstallState.Building, is BuildInstallState.ChoosingInstaller,
        is BuildInstallState.Installing, is BuildInstallState.Conflict -> true
        else -> false
    }

    fun build(expected: ProfileSessionToken) {
        if (closed || busy) return
        val owner = ++operation
        state = BuildInstallState.Building()
        job = scope.launch {
            var pack: BuiltIconPack? = null
            val progress = MutableStateFlow(BuildInstallState.Building())
            val progressObserver = launch {
                progress.collect { building ->
                    if (state is BuildInstallState.Building) publish(owner, building)
                }
            }
            try {
                val preferences = application.dataStore.getPreferencesAfterPendingWrites()
                val built = provider.buildAndSignIconPack(expected, preferences,
                    textMethod = { step -> progress.update { it.copy(step = step, progress = null) } },
                    progressMethod = { done, total -> progress.update { it.copy(progress = done to total) } }
                )
                pack = built
                val wasUpdate = withContext(Dispatchers.IO) {
                    runCatching { application.packageManager.getPackageInfo(built.packageName, 0) }.isSuccess
                }
                val request = PackInstallRequest(built, wasUpdate, preferences.installerSelection())
                val reason = installerChoiceReason(
                    preferences.getBooleanValue(AskInstallerEveryTimeKey),
                    preferences.getBooleanValue(InstallerChoiceConfirmedKey)
                )
                if (reason != null) {
                    publish(owner, BuildInstallState.ChoosingInstaller(request, reason))
                } else if (publish(owner, BuildInstallState.Installing(request))) {
                    installBuiltPack(owner, request, replacing = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileOperationBusyException) {
                fail(owner, R.string.profileRenderBusy)
            } catch (error: ProfileOperationSupersededException) {
                fail(owner, R.string.profileOperationSuperseded)
            } catch (error: ProfileSessionChangedException) {
                fail(owner, error.userMessage())
            } catch (error: Exception) {
                Log.error("BuildInstallCoordinator", "Icon pack build failed", error)
                fail(owner, R.string.iconPackBuildFailed)
            } finally {
                progressObserver.cancel()
                try { pack?.let { releaseUnclaimedBuild(it, owner) } } finally { finishRunning(owner) }
            }
        }
    }

    fun confirmInstallerSelection(selection: InstallerSelection) {
        val choosing = state as? BuildInstallState.ChoosingInstaller ?: return
        startInstall(choosing.request.copy(selection = selection), replacing = false,
            rememberSelection = choosing.reason == InstallerChoiceReason.FIRST_CHOICE)
    }

    fun confirmInstallFallback() {
        val conflict = state as? BuildInstallState.Conflict ?: return
        startInstall(conflict.request, replacing = true)
    }

    private fun startInstall(request: PackInstallRequest, replacing: Boolean, rememberSelection: Boolean = false) {
        if (closed) return
        val owner = ++operation
        // Claim before launching: duplicate taps cannot start a second installation.
        state = BuildInstallState.Installing(request, replacing)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                if (rememberSelection) application.dataStore.persistInstallerSelection(request.selection)
                installBuiltPack(owner, request, replacing)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("BuildInstallCoordinator", "Icon pack installation failed", error)
                fail(owner, request.failedMessage)
            } finally {
                try { releaseUnclaimedBuild(request.pack, owner) } finally { finishRunning(owner) }
            }
        }
    }

    fun dismissInstallerSelection() {
        val choosing = state as? BuildInstallState.ChoosingInstaller ?: return
        finishingPacks += choosing.request.pack
        state = BuildInstallState.Idle
        scope.launch(NonCancellable) {
            try {
                provider.finishWithoutInstallation(choosing.request.pack)
                synchronizeAfterInstall(choosing.request.pack)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("BuildInstallCoordinator", "Finishing without installation failed", error)
                if (!closed) notify(R.string.iconPackBuildFailed)
            } finally {
                releaseFinishingBuild(choosing.request.pack, completed = false)
            }
        }
    }

    fun dismissInstallFallback() {
        val conflict = state as? BuildInstallState.Conflict ?: return
        finishingPacks += conflict.request.pack
        state = BuildInstallState.Idle
        scope.launch(NonCancellable) {
            try {
                synchronizeAfterInstall(conflict.request.pack)
            } finally {
                releaseFinishingBuild(conflict.request.pack)
            }
        }
    }

    fun dismissBuildOutcome() {
        if (state is BuildInstallState.Done) state = BuildInstallState.Idle
    }

    fun beginFailedApkExport(openPicker: (String) -> Unit) {
        if (closed || preparingApkExport || pendingApkExport.lease != null) return
        val pack = (state as? BuildInstallState.Failed)?.pack ?: return
        preparingApkExport = true
        scope.launch {
            var handedOff = false
            var lease: ApkExportLease? = null
            try {
                lease = provider.reserveApkExport(pack.uri)
                if (lease == null) {
                    if (!closed) notify(R.string.installFailureApkSaveFailed)
                    return@launch
                }
                if (closed) return@launch
                pendingApkExport.lease = lease
                openPicker(apkExportFileName(pack))
                handedOff = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("BuildInstallCoordinator", "Could not prepare APK export", error)
                if (!closed) notify(R.string.installFailureApkSaveFailed)
            } finally {
                preparingApkExport = false
                if (!handedOff) {
                    pendingApkExport.lease = null
                    lease?.let { withContext(NonCancellable) { provider.releaseApkExport(it) } }
                }
            }
        }
    }

    // Saving a file never proves installation and never records the profile as built.
    fun finishFailedApkExport(target: Uri?) {
        val lease = pendingApkExport.lease
        pendingApkExport.lease = null
        if (closed || target == null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    lease?.let { provider.releaseApkExport(it) }
                    if (target != null) provider.exportBuiltApk(null, target)
                }
            }
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = provider.exportBuiltApk(lease, target)
            if (!closed) notify(if (saved) R.string.installFailureApkSaved else R.string.installFailureApkSaveFailed)
        }
    }

    fun dismissInstallFailure() {
        if (state is BuildInstallState.Failed) state = BuildInstallState.Idle
    }

    private suspend fun installBuiltPack(owner: Long, request: PackInstallRequest, replacing: Boolean) {
        try {
            installAndFinish(owner, request, replacing)
        } finally {
            if (completingInstallOwner?.operation == owner) completingInstallOwner = null
        }
    }

    private suspend fun installAndFinish(owner: Long, request: PackInstallRequest, replacing: Boolean) {
        val launcher = withContext(Dispatchers.IO) { resolveHomeLauncher(application.packageManager) }
        val onRoute: suspend (ApkInstallRoute) -> Unit = { route ->
            withContext(Dispatchers.Main) {
                if (state is BuildInstallState.Installing) {
                    publish(owner, BuildInstallState.Installing(request, replacing, route))
                }
            }
        }
        val onInstalled: suspend (ApkInstallOutcome) -> Unit = { confirmed ->
            withContext(Dispatchers.Main) {
                if (!closed && owner == operation) {
                    completingInstallOwner = InstallCompletionOwner(owner, request.pack)
                    val installerLabel = confirmed.attempts.lastOrNull { it.result == ApkInstallResult.SUCCESS }
                        ?.installerLabel ?: application.getString(R.string.installerSystem)
                    val wasUpdate = if (replacing) request.wasUpdate else confirmed.wasUpdate ?: request.wasUpdate
                    publish(owner, BuildInstallState.Done(
                        wasUpdate, request.pack.packLabel, installerLabel,
                        savingProfile = true, launcher = launcher
                    ))
                    if (BuildConfig.DEBUG) confirmed.confirmedAtMillis?.let { confirmedAt ->
                        Log.debug("InstallPerformance", "confirmedToUiPublicationMs=${SystemClock.elapsedRealtime() - confirmedAt}")
                    }
                    notifyText(application.getString(
                        installSuccessMessage(confirmed, wasUpdate), request.pack.packLabel, installerLabel
                    ))
                }
            }
        }
        val completion = if (replacing) provider.replaceIconPack(request.pack, request.selection, onRoute, onInstalled)
            else provider.installIconPack(request.pack, request.selection, onRoute, onInstalled)
        val outcome = completion.outcome
        currentCoroutineContext().ensureActive()
        if (closed || owner != operation) return
        when (outcome.result) {
            ApkInstallResult.SUCCESS -> {
                val done = state as? BuildInstallState.Done
                if (done != null) {
                    publish(owner, done.copy(savingProfile = false, profileSaveFailed = completion.profileSaveFailed))
                } else if (completion.profileSaveFailed) {
                    notify(R.string.buildInstalledProfileSaveFailed)
                }
                if (!completion.profileSaveFailed) synchronizeAfterInstall(request.pack, refreshMissing = true)
                else followUp("Missing pack synchronization failed") { refreshMissingPacks() }
            }
            ApkInstallResult.CONFLICT -> if (!replacing) {
                publish(owner, BuildInstallState.Conflict(request, outcome.detail == SIGNATURE_MISMATCH_DETAIL))
                synchronizeAfterInstall(request.pack)
            } else {
                withContext(NonCancellable) { provider.releaseBuiltArtifacts(request.pack) }
                showInstallFailure(owner, outcome, request, launcher)
                synchronizeAfterInstall(request.pack)
            }
            ApkInstallResult.ABORTED -> {
                fail(owner, request.failedMessage)
                synchronizeAfterInstall(request.pack)
            }
            else -> {
                showInstallFailure(owner, outcome, request, launcher)
                synchronizeAfterInstall(request.pack)
            }
        }
    }

    private suspend fun synchronizeAfterInstall(pack: BuiltIconPack, refreshMissing: Boolean = false) {
        followUp("Build baseline synchronization failed") { syncBaselines(pack) }
        if (refreshMissing) followUp("Missing pack synchronization failed") { refreshMissingPacks() }
    }

    private suspend fun followUp(failure: String, update: suspend () -> Unit) {
        if (closed) return
        try {
            update()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The installer result is final; later UI bookkeeping cannot change it.
            Log.error("BuildInstallCoordinator", failure, error)
        }
    }

    private fun showInstallFailure(
        owner: Long, outcome: ApkInstallOutcome, request: PackInstallRequest, launcher: HomeLauncher?
    ) {
        val detail = outcome.detail?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "No additional details were provided by Android."
        val report = buildString {
            appendLine("Renkin icon pack installation report")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Launcher: " + (launcher?.let { "${it.label} (${it.packageName})" } ?: "no default home app"))
            appendLine("Pack: ${request.pack.packageName}")
            appendLine("Requested installer: ${outcome.requestedMethod.name}")
            appendLine("Result: ${outcome.result.name}")
            if (outcome.attempts.isNotEmpty()) appendLine("Routes attempted: " + outcome.attempts.joinToString(" → ") {
                "${it.backend.diagnosticName} (${it.result.name})"
            })
            appendLine()
            append(detail)
        }
        if (publish(owner, BuildInstallState.Failed(request.failedMessage,
            "${outcome.result.name}: ${detail.lineSequence().first().take(300)}", report, outcome.result, request.pack,
            installFailureHelp(outcome.result, outcome.attempts.lastOrNull()?.detail ?: detail,
                android.os.Build.MANUFACTURER)))) notify(request.failedMessage)
    }

    private fun fail(owner: Long, @StringRes message: Int) {
        if (publish(owner, BuildInstallState.Idle)) notify(message)
    }

    private fun publish(owner: Long, next: BuildInstallState): Boolean {
        if (closed || owner != operation) return false
        state = next
        return true
    }

    private fun finishRunning(owner: Long) {
        if (owner == operation && (state is BuildInstallState.Building || state is BuildInstallState.Installing)) {
            state = BuildInstallState.Idle
        }
    }

    private fun pendingPack(): BuiltIconPack? = when (val current = state) {
        is BuildInstallState.ChoosingInstaller -> current.request.pack
        is BuildInstallState.Conflict -> current.request.pack
        else -> null
    }

    private suspend fun releaseUnclaimedBuild(pack: BuiltIconPack, owner: Long) {
        if (pendingPack() === pack || finishingPacks.any { it === pack }) return
        // A conflict can be confirmed while the previous attempt synchronizes its baseline.
        if (owner != operation && (state as? BuildInstallState.Installing)?.request?.pack === pack) return
        if (owner != operation && completingInstallOwner?.pack === pack) return
        withContext(NonCancellable) { provider.releaseBuiltArtifacts(pack, completed = false) }
    }

    private suspend fun releaseFinishingBuild(pack: BuiltIconPack, completed: Boolean = true) {
        try {
            withContext(NonCancellable) { provider.releaseBuiltArtifacts(pack, completed) }
        } finally {
            finishingPacks.removeAll { it === pack }
        }
    }

    fun close() {
        closed = true
        operation++
        val pending = pendingPack()
        if (pending != null) finishingPacks += pending
        state = BuildInstallState.Idle
        job?.cancel()
        pendingApkExport.lease?.let { lease ->
            pendingApkExport.lease = null
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { provider.releaseApkExport(lease) }
            }
        }
        if (pending != null) scope.launch(NonCancellable) { releaseFinishingBuild(pending) }
    }
}

internal fun apkExportFileName(pack: BuiltIconPack): String {
    val label = pack.packLabel.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim(' ', '.', '_')
    return "${label.ifEmpty { pack.packageName }}.apk"
}

@StringRes
internal fun installSuccessMessage(outcome: ApkInstallOutcome, wasUpdate: Boolean): Int {
    val shizukuFallback = outcome.requestedMethod == InstallMethod.SHIZUKU &&
        outcome.attempts.lastOrNull { it.result == ApkInstallResult.SUCCESS }?.backend
            ?.let { it != ApkInstallBackend.SHIZUKU } == true
    return when {
        shizukuFallback && wasUpdate -> R.string.buildUpdatedShizukuFallbackToast
        shizukuFallback -> R.string.buildInstalledShizukuFallbackToast
        wasUpdate -> R.string.buildUpdatedToast
        else -> R.string.buildInstalledToast
    }
}

internal suspend fun DataStore<Preferences>.persistInstallerSelection(selection: InstallerSelection) {
    setEnumValue(InstallMethodKey, selection.method)
    setStringValue(ExternalInstallerComponentKey, selection.externalComponent)
    setBooleanValue(InstallerChoiceConfirmedKey, true)
}
