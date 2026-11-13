package dev.renkinProject.renkin.util

import android.os.Build
import dev.renkinProject.renkin.BuildConfig

internal object AppBuildInfo {
    val versionLabel: String
        get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    val displayVersion: String
        get() = "$versionLabel · ${BuildConfig.BUILD_TYPE} · ${BuildConfig.SOURCE_REVISION}"

    fun appendTo(report: StringBuilder) {
        report.appendLine("App: $versionLabel")
        report.appendLine("Build: ${BuildConfig.BUILD_TYPE} ${BuildConfig.SOURCE_REVISION}")
        report.appendLine("Package: ${BuildConfig.APPLICATION_ID}")
    }

    fun appendEnvironmentTo(report: StringBuilder) {
        appendTo(report)
        report.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    }
}
