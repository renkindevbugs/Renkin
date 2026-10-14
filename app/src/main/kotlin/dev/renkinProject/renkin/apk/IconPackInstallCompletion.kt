package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class IconPackInstallCompletion(
    val outcome: ApkInstallOutcome,
    val profileSaveFailed: Boolean = false
)

internal suspend fun finishConfirmedInstallation(
    outcome: ApkInstallOutcome,
    onInstalled: suspend (ApkInstallOutcome) -> Unit,
    persist: suspend () -> Unit
): IconPackInstallCompletion = withContext(NonCancellable + Dispatchers.Default) {
    var profileSaveFailed = false
    try {
        try {
            onInstalled(outcome)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("ApplicationProvider", "Could not publish confirmed installation", error)
        }
    } finally {
        try {
            persist()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("ApplicationProvider", "Installed pack profile could not be saved", error)
            profileSaveFailed = true
        }
    }
    IconPackInstallCompletion(outcome, profileSaveFailed)
}
