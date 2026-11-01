package dev.renkinProject.renkin.util

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
}
