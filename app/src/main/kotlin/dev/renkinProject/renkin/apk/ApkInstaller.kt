package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.delay
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.installer.parameters.InstallerType
import ru.solrudev.ackpine.session.parameters.Confirmation

enum class ApkInstallResult {
    SUCCESS,
    CONFLICT,
    ABORTED,
    BLOCKED,
    INCOMPATIBLE,
    INVALID,
    STORAGE,
    TIMEOUT,
    FAILED
}

/** Typed system outcome plus the raw PackageInstaller detail needed for remote diagnostics. */
data class ApkInstallOutcome(
    val result: ApkInstallResult,
    val detail: String? = null
)

internal fun isDeadInstallerSession(outcome: ApkInstallOutcome): Boolean =
    outcome.result == ApkInstallResult.FAILED &&
        outcome.detail?.matches(Regex("Session \\d+ is dead\\.")) == true

internal fun installationAdvanced(previousVersion: Long?, currentVersion: Long?): Boolean =
    currentVersion != null && (previousVersion == null || currentVersion > previousVersion)

class ApkInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val packageInstaller = PackageInstaller.getInstance(appContext)

    suspend fun install(apk: Uri, packageName: String): ApkInstallOutcome {
        val previousVersion = installedVersion(packageName)
        val sessionOutcome = installWith(apk, InstallerType.SESSION_BASED)
        if (!isDeadInstallerSession(sessionOutcome)) return sessionOutcome

        // Some OEM installers remove the platform session before Ackpine receives its final
        // status broadcast. First accept an install that actually completed; otherwise retry
        // through Android's ACTION_INSTALL_PACKAGE path, which doesn't depend on that session.
        if (awaitInstallation(previousVersion, packageName)) {
            return ApkInstallOutcome(ApkInstallResult.SUCCESS)
        }

        Log.error(TAG, "Native installer session died; retrying with intent-based installer")
        val fallbackOutcome = installWith(apk, InstallerType.INTENT_BASED)
        if (fallbackOutcome.result == ApkInstallResult.SUCCESS) return fallbackOutcome
        // The fallback can report just as late as the session it replaced, so the package state
        // — not the reported result — decides whether the user ends up with the new pack.
        if (awaitInstallation(previousVersion, packageName)) {
            return ApkInstallOutcome(ApkInstallResult.SUCCESS)
        }
        return fallbackOutcome.copy(
            detail = buildString {
                appendLine("Session-based installer: ${sessionOutcome.detail}")
                appendLine(
                    "Intent-based fallback: " +
                        (fallbackOutcome.detail ?: "No additional details were provided by Android.")
                )
                append("Installed pack version: previous=${previousVersion ?: "none"}, ")
                append("current=${installedVersion(packageName) ?: "none"}")
            }
        )
    }

    /** Polls the package state, because a dead session says nothing about the install itself. */
    private suspend fun awaitInstallation(previousVersion: Long?, packageName: String): Boolean {
        repeat(DEAD_SESSION_PACKAGE_CHECKS) {
            if (installationAdvanced(previousVersion, installedVersion(packageName))) return true
            delay(DEAD_SESSION_PACKAGE_CHECK_DELAY_MS)
        }
        return false
    }

    private suspend fun installWith(apk: Uri, installerType: InstallerType): ApkInstallOutcome {
        val session = packageInstaller.createSession(apk) {
            confirmation = Confirmation.IMMEDIATE
            this.installerType = installerType
        }
        return session.awaitInstallResult(TAG)
    }

    private fun installedVersion(packageName: String): Long? = runCatching {
        val packageInfo = appContext.packageManager.getPackageInfo(packageName, 0)
        PackageInfoCompat.getLongVersionCode(packageInfo)
    }.getOrNull()

    private companion object {
        const val TAG = "ApkInstaller"
        // Samsung's installer can keep scanning a sideloaded APK for several seconds after the
        // session is gone, so the window has to outlast that scan (32 * 250 ms = 8 s).
        const val DEAD_SESSION_PACKAGE_CHECKS = 32
        const val DEAD_SESSION_PACKAGE_CHECK_DELAY_MS = 250L
    }
}
