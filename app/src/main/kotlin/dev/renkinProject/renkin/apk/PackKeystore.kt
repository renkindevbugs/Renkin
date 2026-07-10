package dev.renkinProject.renkin.apk

import java.io.File
import java.security.SecureRandom

/**
 * Credentials of the generated pack's signing keystore (`renkinpack.keystore` in filesDir).
 *
 * The password is random per install and stored next to the keystore — both files travel in
 * full backups, so packs rebuilt after a device migration keep the signature of the already
 * installed ones. Keystores created before passwords went random (including ones restored
 * from old backups) keep opening with the upstream constant: a keystore present WITHOUT a
 * password file means exactly that legacy case.
 */
object PackKeystore {
    const val FILE_NAME = "renkinpack.keystore"
    const val PASSWORD_FILE_NAME = "renkinpack.keystore.pwd"
    const val KEY_ALIAS = "alias"

    // The upstream Alembicons constant, public in its source history — the reason new
    // keystores get a random password instead.
    private const val LEGACY_PASSWORD = "s3cur3p@ssw0rd"

    fun keystoreFile(filesDir: File): File = File(filesDir, FILE_NAME)

    fun passwordFile(filesDir: File): File = File(filesDir, PASSWORD_FILE_NAME)

    /**
     * The password to open (or create) the pack keystore with:
     *  - the stored random one, when the password file exists;
     *  - the legacy constant, when a keystore exists without a password file (pre-random
     *    installs and old backups) — never upgraded in place, the keystore was created
     *    with the constant and must keep opening with it;
     *  - a fresh random one (persisted first) when there is no keystore yet — the signer
     *    then creates the keystore with it.
     */
    fun password(filesDir: File): String {
        val stored = passwordFile(filesDir)
        if (stored.exists()) return stored.readText()
        if (keystoreFile(filesDir).exists()) return LEGACY_PASSWORD
        val fresh = generatePassword()
        stored.writeText(fresh)
        return fresh
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
