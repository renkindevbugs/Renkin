package dev.renkinProject.renkin.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches a user action whose failure must reach the user instead of the uncaught handler, which
 * would end the process. Cancellation still propagates.
 */
internal fun CoroutineScope.launchGuarded(
    onFailure: (Exception) -> Unit,
    block: suspend CoroutineScope.() -> Unit
): Job = launch {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
    }
}
