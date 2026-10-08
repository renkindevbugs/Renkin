package dev.renkinProject.renkin

import androidx.annotation.StringRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class ProfileActionProgress(
    @StringRes val messageRes: Int,
    val applicationName: String? = null
)

/** Owns the foreground indicator and its optional session-mutex reservation until job completion. */
internal class ForegroundProfileActions(
    private val sessionMutex: Mutex,
    private val onBusy: () -> Unit
) {
    private val mutableProgress = MutableStateFlow<ProfileActionProgress?>(null)
    val progress = mutableProgress.asStateFlow()

    fun launch(progress: ProfileActionProgress, serialize: Boolean, start: () -> Job) {
        if (!mutableProgress.compareAndSet(null, progress)) {
            onBusy()
            return
        }
        val ownsSessionLock = serialize && sessionMutex.tryLock()
        if (serialize && !ownsSessionLock) {
            mutableProgress.compareAndSet(progress, null)
            onBusy()
            return
        }
        fun release() {
            if (ownsSessionLock) sessionMutex.unlock()
            mutableProgress.compareAndSet(progress, null)
        }
        try {
            // Completion also handles cancellation before the coroutine body ever starts.
            start().invokeOnCompletion { release() }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }
}
