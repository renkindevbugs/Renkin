package dev.renkinProject.renkin.apk

/** Immediate readiness and live identity; completed icon snapshots remain in [ApplicationListState]. */
data class ApplicationRuntimeState(
    val sessionToken: ProfileSessionToken,
    val startupComplete: Boolean = false,
    val isProfileSwitching: Boolean = false
) {
    val activeProfileId: Long get() = sessionToken.profileId
    val ready: Boolean get() = startupComplete && !isProfileSwitching
}
