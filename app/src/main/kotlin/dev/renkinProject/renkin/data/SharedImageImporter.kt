package dev.renkinProject.renkin.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies images shared into Renkin into the upload gallery on a process scope, so the copy
 * outlives the share activity that received the URI. Each share is a new import; a request for a
 * URI whose import is still running waits for it instead of copying the image twice.
 */
@Singleton
class SharedImageImporter internal constructor(
    private val library: UploadedImageLibrary,
    private val scope: CoroutineScope
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(StoredUploadedImageLibrary(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val running = mutableMapOf<String, Deferred<Unit>>()
    private val toasts = Channel<Int>(Channel.BUFFERED)

    /** Result notices, collected by MainActivity's toast host even when it starts after the copy. */
    val toastEvents: Flow<Int> = toasts.receiveAsFlow()

    suspend fun import(uri: Uri) {
        val key = uri.toString()
        val import = synchronized(running) {
            running.getOrPut(key) {
                scope.async {
                    try {
                        val added = library.import(listOf(uri)).added.isNotEmpty()
                        toasts.send(if (added) R.string.sharedImageAdded else R.string.uploadImageError)
                    } finally {
                        synchronized(running) { running.remove(key) }
                    }
                }
            }
        }
        import.await()
    }
}
