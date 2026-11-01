package dev.renkinProject.renkin.util

import kotlinx.coroutines.CancellationException

/** [runCatching] that lets a cancelled coroutine stay cancelled instead of reporting a failure. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
