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
 * handler that writes each crash (stack trace + app/device info, no personal data) to its
 * own timestamped file, then chains to the platform handler so the process still dies
 * normally. On the next launch the app detects a new crash and offers the log for manual
 * reporting; the Settings "Crash logs" screen lists the full history.
 *
 * Fully offline — no network and no third-party SDK. Logs live in [filesDir][Context.getFilesDir]
 * (not cache, so the OS won't evict them) and are kept for [RETENTION_DAYS] days, capped at
 * [MAX_ENTRIES] so a crash loop can't fill storage. [prune] enforces both at startup.
 */
object CrashReporter {
    private const val CRASH_DIR = "crashes"
    private const val FILE_PREFIX = "crash_"
    private const val FILE_SUFFIX = ".txt"
    private const val SEEN_FILE = ".seen"
    private const val RETENTION_DAYS = 30L
    private const val RETENTION_MILLIS = RETENTION_DAYS * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 50

    /** One captured crash: [id] is the stable file name, [timestamp] is epoch-millis. */
    data class CrashEntry(val id: String, val timestamp: Long, val text: String)

    /** Records uncaught exceptions to a new crash file; call once from the Application. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, throwable) }
            // Keep the platform's behaviour (system "app stopped" dialog + process death).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Every stored crash, newest first. */
    fun list(context: Context): List<CrashEntry> =
        crashDir(context)
            .listFiles { file -> file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) }
            ?.mapNotNull { file ->
                val ts = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull()
                    ?: return@mapNotNull null
                CrashEntry(file.name, ts, runCatching { file.readText() }.getOrDefault(""))
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    /** The most recent crash, or null when none is stored. */
    fun latest(context: Context): CrashEntry? = list(context).firstOrNull()

    fun delete(context: Context, id: String) {
        runCatching { File(crashDir(context), id).delete() }
    }

    fun clearAll(context: Context) {
        crashDir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * Imports any legacy single-file crash, then drops entries older than [RETENTION_DAYS]
     * and trims the rest to [MAX_ENTRIES]. Cheap; run once at startup off the main thread.
     */
    fun prune(context: Context) {
        migrateLegacy(context)
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        list(context)
            .filter { it.timestamp < cutoff }
            .forEach { delete(context, it.id) }
        list(context)
            .drop(MAX_ENTRIES)
            .forEach { delete(context, it.id) }
    }

    // ---- "previous session crashed" one-shot dialog -------------------------------
    // The launch dialog shows once per new crash. We remember the newest crash the user has
    // acknowledged (a marker file) instead of deleting it, so the history screen keeps it.

    /** True when the newest crash hasn't been acknowledged yet (drives the launch dialog). */
    fun hasNewCrash(context: Context): Boolean {
        val latest = latest(context) ?: return false
        return latest.timestamp > seenTimestamp(context)
    }

    /** Marks the current newest crash as acknowledged so the launch dialog won't repeat. */
    fun markCrashesSeen(context: Context) {
        val latest = latest(context) ?: return
        runCatching { File(crashDir(context), SEEN_FILE).writeText(latest.timestamp.toString()) }
    }

    private fun seenTimestamp(context: Context): Long =
        runCatching { File(crashDir(context), SEEN_FILE).readText().trim().toLong() }.getOrDefault(0L)

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR).apply { mkdirs() }

    private fun writeCrash(context: Context, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Renkin crash report")
            appendLine("Time: ${DateFormat.getDateTimeInstance().format(Date(now))}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(stack)
        }
        File(crashDir(context), "$FILE_PREFIX$now$FILE_SUFFIX").writeText(report)
    }

    /** One-time import of the old single-file crash (cacheDir/crash/last_crash.txt). */
    private fun migrateLegacy(context: Context) {
        val legacy = File(File(context.cacheDir, "crash"), "last_crash.txt")
        if (!legacy.exists()) return
        runCatching {
            val ts = legacy.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            val dest = File(crashDir(context), "$FILE_PREFIX$ts$FILE_SUFFIX")
            if (!dest.exists()) dest.writeText(legacy.readText())
            legacy.delete()
        }
    }
}
