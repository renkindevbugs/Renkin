package dev.renkinProject.renkin.apk

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dev.renkinProject.renkin.util.Log

internal const val SIGNATURE_MISMATCH_DETAIL = "The installed pack is signed with a different key than the new APK."

/**
 * Whether [apkPath] can update the installed [packageName] in place: true when nothing is
 * installed or the installed signer is in the new APK's signing lineage, false on a definite
 * mismatch, and null when either side cannot be read, so Android decides instead of a guess.
 */
@Suppress("DEPRECATION")
internal fun canUpdateWithSameSigner(packageManager: PackageManager, packageName: String, apkPath: String): Boolean? {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    return try {
        val installed = try {
            packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return true
        }
        val built = packageManager.getPackageArchiveInfo(apkPath, flags) ?: return null
        val installedSigners = currentSigners(installed) ?: return null
        val builtLineage = signingLineage(built) ?: return null
        builtLineage.containsAll(installedSigners)
    } catch (error: Exception) {
        Log.error("PackSignatureCheck", "Could not compare pack signing certificates", error)
        null
    }
}

@Suppress("DEPRECATION")
private fun currentSigners(info: PackageInfo): Set<Signature>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signing = info.signingInfo ?: return null
        val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) }
        signers?.toSet()?.takeIf { it.isNotEmpty() }
    } else {
        info.signatures?.toSet()?.takeIf { it.isNotEmpty() }
    }

@Suppress("DEPRECATION")
private fun signingLineage(info: PackageInfo): Set<Signature>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signing = info.signingInfo ?: return null
        val lineage = if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        lineage?.toSet()?.takeIf { it.isNotEmpty() }
    } else {
        info.signatures?.toSet()?.takeIf { it.isNotEmpty() }
    }
