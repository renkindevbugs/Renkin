package dev.renkinProject.renkin

/** Release whose notes ship with this build. Bump both values when adding the next notes. */
const val LATEST_WHATS_NEW_VERSION_CODE = 56
const val LATEST_WHATS_NEW_VERSION_NAME = "2026.11.01"

internal fun shouldShowWhatsNew(
    lastSeenVersion: Int?,
    onboardingSeen: Boolean,
    installedVersion: Int = BuildConfig.VERSION_CODE
): Boolean = LATEST_WHATS_NEW_VERSION_CODE <= installedVersion && when {
    lastSeenVersion == null -> onboardingSeen
    else -> lastSeenVersion < LATEST_WHATS_NEW_VERSION_CODE
}
