package dev.renkinProject.renkin.apk

import android.content.Context
import android.content.pm.PackageManager
import app.revanced.library.ApkSigner
import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Format recognition never grants ownership of the original icon sources. */
object GeneratedPackIdentity {
    internal const val ASSET_NAME = "renkin_pack_identity.txt"
    internal const val MARKER = "renkin-icon-pack:1"
    private const val LEDGER = "generated_pack_signers"
    private val knownGenerated = ConcurrentHashMap.newKeySet<String>()

    fun isKnownGenerated(packageName: String): Boolean =
        IconPackBuilder.isLegacyOwnPack(packageName) || packageName in knownGenerated

    fun isGenerated(context: Context, packageName: String): Boolean {
        if (IconPackBuilder.isLegacyOwnPack(packageName)) return true
        val marked = runCatching {
            context.createPackageContext(packageName, 0).assets.open(ASSET_NAME).use { input ->
                val bytes = ByteArray(MARKER.length + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                count == MARKER.length && String(bytes, 0, count, Charsets.UTF_8) == MARKER
            }
        }.getOrDefault(false)
        if (marked) knownGenerated.add(packageName) else knownGenerated.remove(packageName)
        return marked
    }

    /** Checked against the installed certificate, never just a user-entered or imported name. */
    fun isLocallyOwned(context: Context, packageName: String): Boolean {
        if (!isGenerated(context, packageName)) return false
        val signers = installedSigners(context, packageName) ?: return false
        val recorded = context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
            .getStringSet(packageName, emptySet()).orEmpty()
        if (recorded.isNotEmpty() && recorded == signers) return true
        // Full backups restore the pack key; no separate ledger is needed to regain ownership.
        val local = runCatching {
            val file = PackKeystore.keystoreFile(context.filesDir)
            if (!file.isFile) return@runCatching null
            val password = PackKeystore.password(context.filesDir)
            val keyStore = file.inputStream().use { ApkSigner.readKeyStore(it, password) }
            keyStore.getCertificate(PackKeystore.KEY_ALIAS)?.encoded?.let(::digest)
        }.getOrNull()
        return local != null && signers == setOf(local)
    }

    fun hasForeignInstalledPackage(context: Context, packageName: String): Boolean {
        try {
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: Exception) {
            return true
        }
        return !isLocallyOwned(context, packageName)
    }

    internal fun recordBuilt(context: Context, packageName: String, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, packageSigningFlags())
            ?: error("Built APK signing certificate is unavailable")
        val signers = currentSigners(info)?.map { digest(it.toByteArray()) }?.toSet()
            ?: error("Built APK has no signing certificate")
        check(context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).edit()
            .putStringSet(packageName, signers).commit()) { "Could not retain built pack identity" }
        knownGenerated.add(packageName)
    }

    private fun installedSigners(context: Context, packageName: String): Set<String>? = try {
        currentSigners(context.packageManager.getPackageInfo(packageName, packageSigningFlags()))
            ?.map { digest(it.toByteArray()) }?.toSet()
    } catch (error: Exception) {
        Log.error("GeneratedPackIdentity", "Could not read pack signing certificate", error)
        null
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toLowerHex()
}
