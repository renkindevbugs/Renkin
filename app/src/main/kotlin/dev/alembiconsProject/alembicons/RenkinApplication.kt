package dev.alembiconsProject.alembicons

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.alembiconsProject.alembicons.util.CrashReporter

/**
 * Application entry point for Hilt. [HiltAndroidApp] triggers Hilt's code generation and
 * creates the app-level dependency container that the rest of the app injects from.
 */
@HiltAndroidApp
class RenkinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a local file so the next launch can offer to email
        // the log (see CrashReporter / the crash dialog in MainActivity).
        CrashReporter.install(this)
    }
}
