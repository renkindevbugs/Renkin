package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await

/**
 * Awaits an Ackpine install/uninstall [Session] and reports whether it succeeded. A failure is
 * logged under [tag] and returns false; coroutine cancellation propagates. Shared by
 * [ApkInstaller] and [ApkUninstaller], which otherwise only differ in the session they create.
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
