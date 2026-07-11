package dev.renkinProject.renkin.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The password contract the pack keystore lives by: fresh installs get a persisted random
 * password, pre-existing keystores without one keep opening with the legacy constant, and
 * a stored password always wins. Pure file logic — no Android needed.
 */
class PackKeystoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun freshInstall_generatesAndPersistsRandomPassword() {
        val dir = tmp.newFolder()
        val first = PackKeystore.password(dir)
        // 24 random bytes, hex-encoded.
        assertEquals(48, first.length)
        assertEquals(first, PackKeystore.passwordFile(dir).readText())
        // Stable across calls — the signer must reopen what it created.
        assertEquals(first, PackKeystore.password(dir))
    }

    @Test
    fun legacyKeystoreWithoutPasswordFile_opensWithLegacyConstant() {
        val dir = tmp.newFolder()
        PackKeystore.keystoreFile(dir).writeBytes(byteArrayOf(1))
        // The upstream constant, never upgraded in place — and no password file appears.
        assertEquals("s3cur3p@ssw0rd", PackKeystore.password(dir))
        assertFalse(PackKeystore.passwordFile(dir).exists())
    }

    @Test
    fun storedPasswordWins() {
        val dir = tmp.newFolder()
        PackKeystore.keystoreFile(dir).writeBytes(byteArrayOf(1))
        PackKeystore.passwordFile(dir).writeText("restored-from-backup")
        assertEquals("restored-from-backup", PackKeystore.password(dir))
    }

    @Test
    fun passwordsAreUniquePerInstall() {
        assertNotEquals(PackKeystore.password(tmp.newFolder()), PackKeystore.password(tmp.newFolder()))
    }
}
