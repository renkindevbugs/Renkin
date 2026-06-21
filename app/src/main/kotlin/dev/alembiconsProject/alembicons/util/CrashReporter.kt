package dev.alembiconsProject.alembicons.util

import android.content.Context
import android.os.Build
import dev.alembiconsProject.alembicons.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/**
 * Lightweight, privacy-friendly crash capture: installs a default uncaught-exception
 * handler that writes the stack trace (plus app/device info, no personal data) to a file,
 * then chains to the platform handler so the process still dies normally. On the next
 * launch the app can detect the file and offer to upload it.
 *
 * The log only ever leaves the device if the user taps "Send", and it's posted silently to
 * a Google Form (no third-party SDK, no email-app chooser) — see [FORM_ID] for setup.
 */
object CrashReporter {
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    // --- Google Form crash intake -------------------------------------------------------
    // One-time setup to make sending work:
    //  1. Create a Google Form with a single "Paragraph" (long answer) question.
    //  2. Send → link (🔗) → copy URL: https://docs.google.com/forms/d/e/<FORM_ID>/viewform
    //     FORM_ID is the segment between "/d/e/" and "/viewform".
    //  3. ⋮ menu → "Get pre-filled link" → put any text in the field → "Get link" → the
    //     copied URL contains "entry.<NUMBER>=..." → that NUMBER is FORM_ENTRY_ID.
    // Until both are filled in, [sendReport] is a no-op that reports failure.
    private const val FORM_ID = "PASTE_FORM_ID_HERE"
    private const val FORM_ENTRY_ID = "PASTE_ENTRY_ID_HERE"

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

    /** Silently uploads the crash log to the Google Form. Returns true on success. */
    suspend fun sendReport(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (FORM_ID == "PASTE_FORM_ID_HERE" || FORM_ENTRY_ID == "PASTE_ENTRY_ID_HERE") return@withContext false
        val file = crashFile(context)
        if (!file.exists()) return@withContext false

        val body = ("entry.$FORM_ENTRY_ID=" + URLEncoder.encode(file.readText(), "UTF-8") +
            "&fvv=1&pageHistory=0").toByteArray()
        val url = URL("https://docs.google.com/forms/d/e/$FORM_ID/formResponse")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body) }
            // Forms answers with 200 (or a redirect to the thank-you page) on success.
            connection.responseCode in 200..399
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
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
