package dev.alembiconsProject.alembicons.util

import android.content.Context
import android.os.Build
import dev.alembiconsProject.alembicons.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Lightweight, privacy-friendly crash capture: installs a default uncaught-exception
 * handler that writes the stack trace (plus app/device info, no personal data) to a file,
 * then chains to the platform handler so the process still dies normally. On the next
 * launch the app detects the file and offers the log for manual reporting.
 *
 * Fully offline — no network and no third-party SDK. The crash dialog only lets the user
 * copy the log (to email it or paste into a GitHub issue); nothing leaves the device on
 * its own.
 */
object CrashReporter {
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    /** Records uncaught exceptions to the crash file; call once from the Application. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, throwable) }
            // Keep the platform's behaviour (system "app stopped" dialog + process death).
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrash(context: Context): Boolean = crashFile(context).exists()

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /** The captured crash log, or null if none/unreadable. */
    fun readLog(context: Context): String? {
        val file = crashFile(context)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    private fun crashFile(context: Context): File =
        File(File(context.cacheDir, CRASH_DIR).apply { mkdirs() }, CRASH_FILE)

    private fun writeCrash(context: Context, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Renkin crash report")
            appendLine("Time: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(stack)
        }
        crashFile(context).writeText(report)
    }
}
