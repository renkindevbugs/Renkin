package dev.renkinProject.renkin.apk

enum class InstallFailureHelp {
    HYPEROS_ISOLATION, XIAOMI_INSTALL_VIA_USB, USER_RESTRICTION, SAMSUNG_AUTO_BLOCKER, PLAY_PROTECT
}

private val xiaomiBrands = setOf("xiaomi", "redmi", "poco")

/**
 * Help for a concrete, recognised failure. The device brand only chooses the wording once the
 * failure class itself is known; an unrecognised failure gets no advice rather than a guess.
 */
internal fun installFailureHelp(result: ApkInstallResult, detail: String, manufacturer: String): InstallFailureHelp? {
    val text = detail.uppercase()
    val brand = manufacturer.lowercase()
    return when {
        "INSTALL_FAILED_HYPEROS_ISOLATION_VIOLATION" in text -> InstallFailureHelp.HYPEROS_ISOLATION
        "INSTALL_FAILED_USER_RESTRICTED" in text || "USER RESTRICTION" in text ->
            if (brand in xiaomiBrands) InstallFailureHelp.XIAOMI_INSTALL_VIA_USB else InstallFailureHelp.USER_RESTRICTION
        "INSTALL_FAILED_VERIFICATION_FAILURE" in text || "PLAY PROTECT" in text -> InstallFailureHelp.PLAY_PROTECT
        result == ApkInstallResult.BLOCKED && brand == "samsung" -> InstallFailureHelp.SAMSUNG_AUTO_BLOCKER
        else -> null
    }
}
