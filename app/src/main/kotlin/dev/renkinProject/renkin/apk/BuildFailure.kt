package dev.renkinProject.renkin.apk

import android.app.ActivityManager
import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.StringRes
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.util.AppBuildInfo
import java.io.PrintWriter
import java.io.StringWriter

internal class PackSigningException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class IconEncodingException(resourceName: String) : Exception("Could not encode $resourceName")

/** Why a pack build stopped, with the advice the build failure dialog gives for it. */
internal enum class BuildFailureCause(@get:StringRes val advice: Int) {
    STORAGE_FULL(R.string.buildFailureStorage),
    OUT_OF_MEMORY(R.string.buildFailureMemory),
    ICON_ENCODING(R.string.buildFailureIconEncoding),
    SIGNING(R.string.buildFailureSigning),
    UNKNOWN(R.string.buildFailureUnknown)
}

internal fun Throwable.buildFailureCause(): BuildFailureCause {
    val chain = generateSequence(this) { it.cause }.take(16).toList()
    return when {
        chain.any { it.isOutOfStorage() } -> BuildFailureCause.STORAGE_FULL
        chain.any { it is OutOfMemoryError } -> BuildFailureCause.OUT_OF_MEMORY
        chain.any { it is IconEncodingException } -> BuildFailureCause.ICON_ENCODING
        chain.any { it is PackSigningException } -> BuildFailureCause.SIGNING
        else -> BuildFailureCause.UNKNOWN
    }
}

private fun Throwable.isOutOfStorage(): Boolean =
    (this is ErrnoException && errno == OsConstants.ENOSPC) ||
        message?.let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) } == true

internal fun buildFailureReport(context: Context, error: Throwable, cause: BuildFailureCause, phase: String): String {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val runtime = Runtime.getRuntime()
    val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    return buildString {
        appendLine("Renkin icon pack build report")
        AppBuildInfo.appendEnvironmentTo(this)
        appendLine("Cause: ${cause.name}")
        appendLine("Phase: ${phase.ifBlank { "starting" }}")
        appendLine("Memory: app limit ${activityManager?.memoryClass ?: "?"} MB, large ${activityManager?.largeMemoryClass ?: "?"} MB, " +
            "low-RAM device ${activityManager?.isLowRamDevice ?: "?"}")
        appendLine("Heap: ${(runtime.totalMemory() - runtime.freeMemory()).toMegabytes()} of ${runtime.maxMemory().toMegabytes()} MB used")
        appendLine("Free storage: ${context.filesDir.usableSpace.toMegabytes()} MB")
        appendLine()
        append(stack)
    }
}

private fun Long.toMegabytes(): Long = this / (1024 * 1024)
