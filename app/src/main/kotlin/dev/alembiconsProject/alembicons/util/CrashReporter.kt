package dev.alembiconsProject.alembicons.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.alembiconsProject.alembicons.BuildConfig
import dev.alembiconsProject.alembicons.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Lightweight, privacy-friendly crash capture: installs a default uncaught-exception
 * handler that writes the stack trace (plus app/device info, no personal data) to a file,
 * then chains to the platform handler so the process still dies normally. On the next
 * launch the app can detect the file and offer to email it. No third-party SDK, and the
 * log only ever leaves the device if the user taps "Send".
 */
object CrashReporter {
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    /** Records uncaught exceptions to [crashFile]; call once from the Application. */
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

    /** Opens an email chooser to send [crashFile] to the project's bug address. */
    fun sendReport(context: Context) {
        val file = crashFile(context)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("renkin.dev.bugs@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, "Renkin crash report")
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.crashEmailBody))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.crashSendChooser))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
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
