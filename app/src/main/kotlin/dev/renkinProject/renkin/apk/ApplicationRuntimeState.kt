package dev.renkinProject.renkin.apk

/** Immediate readiness; completed icon/session snapshots remain in [ApplicationListState]. */
data class ApplicationRuntimeState(
    val startupComplete: Boolean = false,
    val isProfileSwitching: Boolean = false
) {
    val ready: Boolean get() = startupComplete && !isProfileSwitching
}
