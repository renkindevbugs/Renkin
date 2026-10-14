package dev.renkinProject.renkin.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

object ExternalInstallAwaiter {
    private val foregroundGeneration = AtomicLong(0L)

    fun currentGeneration(): Long = foregroundGeneration.get()

    fun onRenkinForegrounded() {
        foregroundGeneration.incrementAndGet()
    }
}

/** One registration per attempt; broadcasts only wake the package-state verifier. */
internal class PackageInstallSignal(context: Context, packageName: String) : AutoCloseable {
    private val appContext = context.applicationContext
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
                intent.action != Intent.ACTION_PACKAGE_REPLACED
            ) return
            if (intent.data?.scheme == "package" && intent.data?.schemeSpecificPart == packageName) {
                changes.trySend(Unit)
            }
        }
    }
    private val registered = try {
        ContextCompat.registerReceiver(
            appContext, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        true
    } catch (error: Exception) {
        Log.error("ApkInstaller", "Package receiver unavailable; using polling", error)
        false
    }

    suspend fun awaitChange(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { changes.receive(); true } ?: false

    override fun close() {
        if (registered) runCatching { appContext.unregisterReceiver(receiver) }
            .onFailure { Log.error("ApkInstaller", "Could not unregister package receiver", it) }
        changes.close()
    }
}
