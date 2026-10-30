package dev.renkinProject.renkin.apk

import android.content.Context
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.Profile

internal enum class ProfilePackIdentityError { INVALID_PACKAGE, RESERVED_PACKAGE, PROFILE_COLLISION, INSTALLED_COLLISION }

internal class ProfilePackIdentityException(val reason: ProfilePackIdentityError) :
    IllegalArgumentException("Profile pack identity rejected: $reason")

internal fun automaticPackPackageName(profileId: Long): String =
    if (profileId == DEFAULT_PROFILE_ID) IconPackBuilder.PACKAGE_NAME
    else "${IconPackBuilder.PACKAGE_NAME}.p$profileId"

internal fun Profile.effectivePackPackageName(): String =
    customPackageName.ifEmpty { automaticPackPackageName(id) }

private val packageNamePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val automaticProfileSuffix = Regex("p[0-9]+")

internal fun validateProfilePackIdentity(
    context: Context,
    profileId: Long?,
    customPackageName: String,
    profiles: List<Profile>,
    checkInstalled: Boolean = true
) {
    if (customPackageName.isEmpty()) return
    fun reject(reason: ProfilePackIdentityError): Nothing = throw ProfilePackIdentityException(reason)
    if (customPackageName.length > 255 || !packageNamePattern.matches(customPackageName)) {
        reject(ProfilePackIdentityError.INVALID_PACKAGE)
    }
    val automaticName = profileId?.let(::automaticPackPackageName)
    val reservedAutomaticName = customPackageName == IconPackBuilder.PACKAGE_NAME ||
        customPackageName.startsWith("${IconPackBuilder.PACKAGE_NAME}.") &&
        automaticProfileSuffix.matches(customPackageName.removePrefix("${IconPackBuilder.PACKAGE_NAME}."))
    if (customPackageName == context.packageName ||
        reservedAutomaticName && customPackageName != automaticName) {
        reject(ProfilePackIdentityError.RESERVED_PACKAGE)
    }
    if (profiles.any { it.id != profileId &&
            (it.effectivePackPackageName() == customPackageName || automaticPackPackageName(it.id) == customPackageName) }) {
        reject(ProfilePackIdentityError.PROFILE_COLLISION)
    }
    if (checkInstalled && customPackageName != automaticName &&
        GeneratedPackIdentity.hasForeignInstalledPackage(context, customPackageName)) {
        reject(ProfilePackIdentityError.INSTALLED_COLLISION)
    }
}
