package dev.renkinProject.renkin.apk

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.installer.parameters.InstallerType
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.shizuku.ShizukuPlugin
import java.io.File

enum class ApkInstallResult {
    SUCCESS,
    CONFLICT,
    ABORTED,
    BLOCKED,
    INCOMPATIBLE,
    INVALID,
    STORAGE,
    TIMEOUT,
    FAILED,
    NO_SYSTEM_INSTALLER
}

enum class ApkInstallBackend(val diagnosticName: String) {
    SYSTEM_SESSION("System session"),
    SHIZUKU("Shizuku"),
    EXTERNAL("Selected external installer"),
    COMPATIBILITY("Android compatibility intent")
}

data class ApkInstallAttempt(
    val backend: ApkInstallBackend,
    val result: ApkInstallResult,
    val detail: String? = null,
    val installerLabel: String? = null
)

data class ApkInstallRoute(val backend: ApkInstallBackend, val label: String)

data class ApkInstallOutcome(
    val result: ApkInstallResult,
    val detail: String? = null,
    val requestedMethod: InstallMethod = InstallMethod.SYSTEM,
    val attempts: List<ApkInstallAttempt> = emptyList(),
    val wasUpdate: Boolean? = null,
    val confirmedAtMillis: Long? = null
)

internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal data class InstalledPackageState(
    val versionCode: Long,
    val lastUpdateTime: Long
)

private val deadSessionPatterns = listOf(
    Regex("(?:PackageInstaller\\.)?Session\\s+\\d+\\s+is\\s+(?:dead|abandoned)\\.?", RegexOption.IGNORE_CASE),
    Regex("Package installer session\\s+\\d+\\s+was\\s+abandoned\\.?", RegexOption.IGNORE_CASE)
)

internal fun isDeadInstallerSession(outcome: ApkInstallOutcome): Boolean =
    outcome.result == ApkInstallResult.FAILED && outcome.detail
        ?.lineSequence()
        ?.any { line -> deadSessionPatterns.any { it.containsMatchIn(line) } } == true

internal fun installationChanged(
    previous: InstalledPackageState?,
    current: InstalledPackageState?
): Boolean = current != null && (
    previous == null ||
        current.versionCode > previous.versionCode ||
        current.versionCode == previous.versionCode && current.lastUpdateTime > previous.lastUpdateTime
    )

class ApkInstaller(
    context: Context,
    private val onRoute: suspend (ApkInstallRoute) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val packageInstaller = PackageInstaller.getInstance(appContext)

    suspend fun install(
        apk: Uri,
        packageName: String,
        method: InstallMethod = InstallMethod.SYSTEM,
        externalComponent: String = ""
    ): ApkInstallOutcome {
        val previous = installedState(packageName)
        val digest = InstalledApkDigest(appContext, apk)
        val started = SystemClock.elapsedRealtime()
        val outcome = when (method) {
            InstallMethod.SYSTEM -> installThroughSystem(apk, packageName, previous, method, digest = digest)
            InstallMethod.SHIZUKU -> installThroughShizuku(apk, packageName, previous, digest)
            InstallMethod.EXTERNAL -> installThroughExternal(
                apk, packageName, previous, externalComponent, digest
            )
        }
        val confirmedAt = SystemClock.elapsedRealtime().takeIf { outcome.result == ApkInstallResult.SUCCESS }
        if (BuildConfig.DEBUG) Log.debug("InstallPerformance",
            "installerResult=${outcome.result} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        return outcome.copy(wasUpdate = previous != null, confirmedAtMillis = confirmedAt)
    }

    private suspend fun installThroughExternal(
        apk: Uri,
        packageName: String,
        previous: InstalledPackageState?,
        flattenedComponent: String,
        digest: InstalledApkDigest
    ): ApkInstallOutcome {
        val component = ComponentName.unflattenFromString(flattenedComponent)
        val sharedApk = runCatching { shareableApkUri(apk) }.getOrElse { error ->
            val failedShare = ApkInstallAttempt(
                ApkInstallBackend.EXTERNAL,
                ApkInstallResult.FAILED,
                "Could not create a content URI for the selected installer: ${error.stackTraceToString()}"
            )
            return installThroughSystem(
                apk = apk,
                packageName = packageName,
                previous = previous,
                requestedMethod = InstallMethod.EXTERNAL,
                digest = digest,
                precedingAttempts = listOf(failedShare)
            )
        }
        if (component == null || !isExternalComponentAvailable(component, sharedApk)) {
            val unavailable = ApkInstallAttempt(
                ApkInstallBackend.EXTERNAL,
                ApkInstallResult.BLOCKED,
                "The selected installer is no longer available: $flattenedComponent"
            )
            return installThroughSystem(
                apk = apk,
                packageName = packageName,
                previous = previous,
                requestedMethod = InstallMethod.EXTERNAL,
                digest = digest,
                precedingAttempts = listOf(unavailable)
            )
        }

        val label = InstallerCatalog(appContext).labelFor(InstallerSelection(InstallMethod.EXTERNAL, flattenedComponent))
            ?: component.packageName
        val result = launchAndAwaitExternalInstaller(sharedApk, packageName, previous, component, label)
        val attempt = ApkInstallAttempt(ApkInstallBackend.EXTERNAL, result.first, result.second, label)
        if (result.first == ApkInstallResult.FAILED) {
            return installThroughSystem(
                apk = apk,
                packageName = packageName,
                previous = previous,
                requestedMethod = InstallMethod.EXTERNAL,
                digest = digest,
                precedingAttempts = listOf(attempt)
            )
        }
        return ApkInstallOutcome(
            result = result.first,
            detail = result.second,
            requestedMethod = InstallMethod.EXTERNAL,
            attempts = listOf(attempt)
        )
    }

    private suspend fun launchAndAwaitExternalInstaller(
        apk: Uri,
        packageName: String,
        previous: InstalledPackageState?,
        component: ComponentName,
        installerLabel: String
    ): Pair<ApkInstallResult, String> {
        val returnGeneration = ExternalInstallAwaiter.currentGeneration()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apk, APK_MIME_TYPE)
            this.component = component
            clipData = ClipData.newRawUri("APK", apk)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, appContext.packageName)
        }
        val signal = PackageInstallSignal(appContext, packageName)
        return try {
            appContext.grantUriPermission(
                component.packageName,
                apk,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            onRoute(ApkInstallRoute(ApkInstallBackend.EXTERNAL, installerLabel))
            withContext(Dispatchers.Main) { appContext.startActivity(intent) }

            val deadline = SystemClock.elapsedRealtime() + EXTERNAL_INSTALL_TIMEOUT_MS
            var returnedAt: Long? = null
            while (true) {
                if (installationChanged(previous, installedState(packageName))) {
                    return ApkInstallResult.SUCCESS to
                        "Package state confirmed installation through ${component.packageName}."
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) break
                if (ExternalInstallAwaiter.currentGeneration() > returnGeneration) {
                    if (returnedAt == null) returnedAt = now
                    // Installers such as InstallerX close their UI and keep installing in the
                    // background, so returning to Renkin only means "cancelled" once no install
                    // session of theirs is still making progress.
                    if (now - returnedAt >= EXTERNAL_RETURN_GRACE_MS &&
                        !hasActiveInstallSession(component.packageName, packageName)
                    ) {
                        // Recheck after the session query: it may have just completed.
                        if (installationChanged(previous, installedState(packageName))) {
                            return ApkInstallResult.SUCCESS to "Package state confirmed installation after return."
                        }
                        return ApkInstallResult.ABORTED to
                            "${component.packageName} returned without installing the package."
                    }
                }
                signal.awaitChange(minOf(EXTERNAL_INSTALL_PACKAGE_CHECK_DELAY_MS, deadline - now))
            }
            ApkInstallResult.TIMEOUT to
                "Timed out while waiting for ${component.packageName} to install $packageName."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error(TAG, "External installer could not be launched", e)
            ApkInstallResult.FAILED to e.stackTraceToString()
        } finally {
            signal.close()
            runCatching { appContext.revokeUriPermission(apk, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun hasActiveInstallSession(installerPackage: String, packageName: String): Boolean =
        runCatching {
            appContext.packageManager.packageInstaller.allSessions.any { session ->
                session.isActive && (
                    session.appPackageName == packageName ||
                        session.installerPackageName == installerPackage
                    )
            }
        }.getOrDefault(false)

    private fun isExternalComponentAvailable(component: ComponentName, apk: Uri): Boolean =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apk, APK_MIME_TYPE)
            this.component = component
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.resolveActivity(appContext.packageManager) != null

    /** Ackpine accepts the builder's file URI internally; another app must receive content://. */
    private fun shareableApkUri(apk: Uri): Uri = when (apk.scheme) {
        "content" -> apk
        "file", null -> FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileProvider",
            File(requireNotNull(apk.path) { "APK file URI has no path" })
        )
        else -> error("Unsupported APK URI scheme: ${apk.scheme}")
    }

    private suspend fun installThroughShizuku(
        apk: Uri,
        packageName: String,
        previous: InstalledPackageState?,
        digest: InstalledApkDigest
    ): ApkInstallOutcome {
        val readiness = ShizukuSupport.state(appContext)
        val initialAttempts = if (readiness == ShizukuState.READY) {
            val outcome = installAndVerify(apk, ApkInstallBackend.SHIZUKU, packageName, previous, digest)
            if (outcome.result == ApkInstallResult.SUCCESS) {
                return outcome.copy(requestedMethod = InstallMethod.SHIZUKU)
            }
            if (outcome.result == ApkInstallResult.ABORTED) {
                return finishOutcome(InstallMethod.SHIZUKU, outcome.attempts, outcome, previous, packageName)
            }
            outcome.attempts
        } else {
            listOf(
                ApkInstallAttempt(
                    backend = ApkInstallBackend.SHIZUKU,
                    result = if (readiness == ShizukuState.ERROR) {
                        ApkInstallResult.FAILED
                    } else {
                        ApkInstallResult.BLOCKED
                    },
                    detail = "Shizuku is unavailable: ${readiness.name}."
                )
            )
        }

        Log.debug(TAG, "Shizuku install unavailable or failed; retrying with system installer")
        return installThroughSystem(
            apk = apk,
            packageName = packageName,
            previous = previous,
            requestedMethod = InstallMethod.SHIZUKU,
            digest = digest,
            precedingAttempts = initialAttempts
        )
    }

    private suspend fun installThroughSystem(
        apk: Uri,
        packageName: String,
        previous: InstalledPackageState?,
        requestedMethod: InstallMethod,
        digest: InstalledApkDigest,
        precedingAttempts: List<ApkInstallAttempt> = emptyList()
    ): ApkInstallOutcome {
        val systemAvailable = withContext(Dispatchers.IO) {
            hasSystemInstallActivity(appContext.packageManager, shareableApkUri(apk))
        }
        if (!systemAvailable) {
            val detail = "No enabled system activity handles CONFIRM_INSTALL or INSTALL_PACKAGE. No system session was started."
            return missingSystemInstaller(ApkInstallBackend.SYSTEM_SESSION, requestedMethod, precedingAttempts, detail)
        }
        val sessionOutcome = installAndVerify(apk, ApkInstallBackend.SYSTEM_SESSION, packageName, previous, digest)
        val attempts = precedingAttempts + sessionOutcome.attempts
        if (!isDeadInstallerSession(sessionOutcome)) {
            return finishOutcome(requestedMethod, attempts, sessionOutcome, previous, packageName)
        }

        Log.error(TAG, "Native installer session died; retrying with compatibility installer")
        return installThroughCompatibility(
            apk = apk,
            packageName = packageName,
            previous = previous,
            requestedMethod = requestedMethod,
            digest = digest,
            precedingAttempts = attempts
        )
    }

    private suspend fun installThroughCompatibility(
        apk: Uri,
        packageName: String,
        previous: InstalledPackageState?,
        requestedMethod: InstallMethod,
        digest: InstalledApkDigest,
        precedingAttempts: List<ApkInstallAttempt>
    ): ApkInstallOutcome {
        val compatibilityAvailable = withContext(Dispatchers.IO) {
            hasSystemInstallActivity(appContext.packageManager, shareableApkUri(apk), includeSessionConfirmation = false)
        }
        if (!compatibilityAvailable) {
            return missingSystemInstaller(ApkInstallBackend.COMPATIBILITY, requestedMethod, precedingAttempts,
                "No enabled system activity handles INSTALL_PACKAGE. No compatibility session was started.")
        }
        val fallbackOutcome = installAndVerify(apk, ApkInstallBackend.COMPATIBILITY, packageName, previous, digest)
        val attempts = precedingAttempts + fallbackOutcome.attempts
        if (fallbackOutcome.result == ApkInstallResult.SUCCESS) {
            return fallbackOutcome.copy(requestedMethod = requestedMethod, attempts = attempts)
        }
        return finishOutcome(requestedMethod, attempts, fallbackOutcome, previous, packageName)
    }

    private fun missingSystemInstaller(
        backend: ApkInstallBackend,
        requestedMethod: InstallMethod,
        precedingAttempts: List<ApkInstallAttempt>,
        detail: String
    ): ApkInstallOutcome = ApkInstallOutcome(
        ApkInstallResult.NO_SYSTEM_INSTALLER, detail, requestedMethod,
        precedingAttempts + ApkInstallAttempt(backend, ApkInstallResult.NO_SYSTEM_INSTALLER, detail,
            appContext.getString(R.string.installerSystem))
    )

    private suspend fun installAndVerify(
        apk: Uri,
        backend: ApkInstallBackend,
        packageName: String,
        previous: InstalledPackageState?,
        digest: InstalledApkDigest
    ): ApkInstallOutcome {
        val outcome = installWith(apk, backend)
        if (!shouldVerifyLateResult(outcome.result)) return outcome
        val timeout = if (isDeadInstallerSession(outcome)) DEAD_SESSION_VERIFY_TIMEOUT_MS else AMBIGUOUS_VERIFY_TIMEOUT_MS
        var verificationDetail = "No changed installed package was confirmed within the ${timeout}ms verification window."
        val confirmed = PackageInstallSignal(appContext, packageName).use { signal ->
            withTimeoutOrNull(timeout) {
                var checked: InstalledPackageState? = null
                while (true) {
                    val current = installedState(packageName)
                    if (installationChanged(previous, current) && current != checked) {
                        when (digest.matches(packageName)) {
                            null -> {
                                verificationDetail = "The package changed, but the submitted or installed APK could not be read."
                                return@withTimeoutOrNull false
                            }
                            true -> if (installedState(packageName) == current) return@withTimeoutOrNull true
                            false -> {
                                verificationDetail = "The package changed, but its SHA-256 did not match the submitted APK."
                                checked = current
                            }
                        }
                    }
                    signal.awaitChange(EXTERNAL_INSTALL_PACKAGE_CHECK_DELAY_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true
        }
        if (confirmed) return verifiedSuccess(outcome, packageName)
        val detail = listOfNotNull(outcome.detail, "Verification: $verificationDetail").joinToString("\n")
        return outcome.copy(detail = detail, attempts = outcome.attempts.map { it.copy(detail = detail) })
    }

    private suspend fun installWith(
        apk: Uri,
        backend: ApkInstallBackend
    ): ApkInstallOutcome {
        val label = appContext.getString(when (backend) {
            ApkInstallBackend.SHIZUKU -> R.string.installerShizuku
            ApkInstallBackend.SYSTEM_SESSION, ApkInstallBackend.COMPATIBILITY -> R.string.installerSystem
            ApkInstallBackend.EXTERNAL -> error("External installers do not use Ackpine sessions")
        })
        onRoute(ApkInstallRoute(backend, label))
        val rawOutcome = try {
            val session = packageInstaller.createSession(apk) {
                confirmation = Confirmation.IMMEDIATE
                installerType = when (backend) {
                    ApkInstallBackend.COMPATIBILITY -> InstallerType.INTENT_BASED
                    ApkInstallBackend.EXTERNAL -> error("External installers do not use Ackpine sessions")
                    ApkInstallBackend.SYSTEM_SESSION,
                    ApkInstallBackend.SHIZUKU -> InstallerType.SESSION_BASED
                }
                if (backend == ApkInstallBackend.SHIZUKU) {
                    plugin(
                        ShizukuPlugin::class,
                        ShizukuPlugin.InstallParameters.Builder()
                            .setReplaceExisting(true)
                            .build()
                    )
                }
            }
            session.awaitInstallResult(TAG)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error(TAG, "${backend.diagnosticName} could not create or run a session", e)
            ApkInstallOutcome(ApkInstallResult.FAILED, e.stackTraceToString())
        }
        return rawOutcome.copy(
            attempts = listOf(ApkInstallAttempt(backend, rawOutcome.result, rawOutcome.detail, label))
        )
    }

    private suspend fun finishOutcome(
        requestedMethod: InstallMethod,
        attempts: List<ApkInstallAttempt>,
        finalOutcome: ApkInstallOutcome,
        previous: InstalledPackageState?,
        packageName: String
    ): ApkInstallOutcome {
        if (finalOutcome.result == ApkInstallResult.SUCCESS) {
            return finalOutcome.copy(requestedMethod = requestedMethod, attempts = attempts)
        }
        val current = installedState(packageName)
        return finalOutcome.copy(
            detail = buildDiagnosticDetail(attempts, previous, current),
            requestedMethod = requestedMethod,
            attempts = attempts
        )
    }

    private fun verifiedSuccess(
        outcome: ApkInstallOutcome,
        packageName: String
    ) = outcome.copy(
        result = ApkInstallResult.SUCCESS,
        detail = "Package state and submitted APK SHA-256 confirmed installation after ${outcome.result.name}.",
        attempts = outcome.attempts + ApkInstallAttempt(
            backend = outcome.attempts.last().backend,
            result = ApkInstallResult.SUCCESS,
            detail = "Package state changed and the installed APK for $packageName matches the submitted artifact after ${outcome.result.name}.",
            installerLabel = outcome.attempts.last().installerLabel
        )
    )

    private suspend fun installedState(packageName: String): InstalledPackageState? = withContext(Dispatchers.IO) {
        try {
            val packageInfo = appContext.packageManager.getPackageInfo(packageName, 0)
            InstalledPackageState(PackageInfoCompat.getLongVersionCode(packageInfo), packageInfo.lastUpdateTime)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun buildDiagnosticDetail(
        attempts: List<ApkInstallAttempt>,
        previous: InstalledPackageState?,
        current: InstalledPackageState?
    ): String = buildString {
        attempts.forEachIndexed { index, attempt ->
            appendLine("Attempt ${index + 1}: ${attempt.backend.diagnosticName} — ${attempt.result.name}")
            appendLine(attempt.detail ?: "No additional details were provided by Android.")
        }
        append("Installed pack before: ${previous.describe()}; after: ${current.describe()}")
    }

    private fun InstalledPackageState?.describe(): String = this?.let {
        "version=${it.versionCode}, lastUpdateTime=${it.lastUpdateTime}"
    } ?: "not installed"

    private fun shouldVerifyLateResult(result: ApkInstallResult): Boolean =
        result == ApkInstallResult.ABORTED || result == ApkInstallResult.FAILED || result == ApkInstallResult.TIMEOUT

    private companion object {
        const val TAG = "ApkInstaller"
        // Samsung's installer can keep scanning a sideloaded APK for several seconds after the
        // session is gone, so the window has to outlast that scan (32 * 250 ms = 8 s).
        const val DEAD_SESSION_VERIFY_TIMEOUT_MS = 8_000L
        const val AMBIGUOUS_VERIFY_TIMEOUT_MS = 3_000L
        const val EXTERNAL_INSTALL_TIMEOUT_MS = 120_000L
        const val EXTERNAL_RETURN_GRACE_MS = 2_000L
        const val EXTERNAL_INSTALL_PACKAGE_CHECK_DELAY_MS = 250L
    }
}
