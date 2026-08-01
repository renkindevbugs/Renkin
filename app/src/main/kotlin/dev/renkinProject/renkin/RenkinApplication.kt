package dev.renkinProject.renkin

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.HiltAndroidApp
import dev.renkinProject.renkin.util.CrashReporter

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
        registerCrashLogsShortcut()
    }

    /**
     * Long-press the launcher icon → the crash logs. Registered here rather than as a static
     * `<shortcut>` resource because those need the package name spelled out, and debug builds
     * install under a different one. Shortcuts persist, so the entry stays available on the very
     * launch that fails — which is exactly when it is needed.
     */
    private fun registerCrashLogsShortcut() {
        runCatching {
            val intent = Intent(this, CrashLogsActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
            val shortcut = ShortcutInfoCompat.Builder(this, "crashLogs")
                .setShortLabel(getString(R.string.crashLogsShortcutShort))
                .setLongLabel(getString(R.string.crashLogsShortcutLong))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
    }
}
