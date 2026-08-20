package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await

/**
 * Awaits an Ackpine install/uninstall [Session] and reports whether it succeeded. A failure is
 * logged under [tag] and returns false; coroutine cancellation propagates. The uninstaller uses
 * this boolean form; the installer keeps its typed failure via [awaitInstallResult].
 */
suspend fun Session<*>.awaitSucceeded(tag: String): Boolean =
    try {
        when (val result = await()) {
            is Session.State.Succeeded -> true
            is Session.State.Failed -> {
                Log.error(tag, "Session failed: ${result.failure}")
                false
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.error(tag, "Session error", e)
        false
    }

internal fun installFailureResult(failure: InstallFailure): ApkInstallResult = when (failure) {
    is InstallFailure.Conflict -> ApkInstallResult.CONFLICT
    is InstallFailure.Aborted -> ApkInstallResult.ABORTED
    is InstallFailure.Blocked -> ApkInstallResult.BLOCKED
    is InstallFailure.Incompatible -> ApkInstallResult.INCOMPATIBLE
    is InstallFailure.Invalid -> ApkInstallResult.INVALID
    is InstallFailure.Storage -> ApkInstallResult.STORAGE
    is InstallFailure.Timeout -> ApkInstallResult.TIMEOUT
    is InstallFailure.Exceptional,
    is InstallFailure.Generic -> ApkInstallResult.FAILED
    else -> ApkInstallResult.FAILED
}

private fun installFailureDetail(failure: InstallFailure): String? =
    when (failure) {
        is InstallFailure.Exceptional -> failure.exception.stackTraceToString()
        else -> failure.message
    }

/** Keeps the install failure category so callers never treat user cancellation like a conflict. */
suspend fun Session<InstallFailure>.awaitInstallResult(tag: String): ApkInstallOutcome =
    try {
        when (val result = await()) {
            is Session.State.Succeeded -> ApkInstallOutcome(ApkInstallResult.SUCCESS)
            is Session.State.Failed -> {
                Log.error(tag, "Session failed: ${result.failure}")
                ApkInstallOutcome(
                    result = installFailureResult(result.failure),
                    detail = installFailureDetail(result.failure)
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.error(tag, "Session error", e)
        ApkInstallOutcome(ApkInstallResult.FAILED, e.stackTraceToString())
    }
