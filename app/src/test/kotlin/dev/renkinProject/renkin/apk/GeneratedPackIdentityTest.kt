package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.res.AssetManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [27])
class GeneratedPackIdentityTest {
    @get:Rule val files = TemporaryFolder()
    private val managers = mutableListOf<AssetManager>()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun closeAssets() { managers.forEach { it.close() } }

    @Test
    fun exactGeneratedMarkerIsRecognizedButDoesNotGrantLocalOwnership() {
        val name = "example.marker.only"
        install(name, "foreign-certificate")
        val marked = packContext(name, GeneratedPackIdentity.MARKER)
        assertTrue(GeneratedPackIdentity.isGenerated(marked, name))
        assertTrue(GeneratedPackIdentity.isKnownGenerated(name))
        assertFalse(GeneratedPackIdentity.isLocallyOwned(marked, name))
        assertTrue(GeneratedPackIdentity.hasForeignInstalledPackage(marked, name))
    }

    @Test
    fun invalidOrExtendedMarkerCannotClaimGeneratedFormatAndClearsCachedClassification() {
        val name = "example.marker.replaced"
        assertTrue(GeneratedPackIdentity.isGenerated(packContext(name, GeneratedPackIdentity.MARKER), name))
        for (marker in listOf("renkin-icon-pack:2", GeneratedPackIdentity.MARKER + "\n", "")) {
            assertFalse(GeneratedPackIdentity.isGenerated(packContext(name, marker), name))
            assertFalse(GeneratedPackIdentity.isKnownGenerated(name))
        }
    }

    @Test
    fun recordedBuildRequiresTheInstalledSignerToMatchAndRetainsPreviousNames() {
        val firstName = "example.signed.first"
        val secondName = "example.signed.second"
        for (name in listOf(firstName, secondName)) {
            val apk = files.newFile()
            shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, packageInfo(name, "our-certificate"))
            GeneratedPackIdentity.recordBuilt(context, name, apk)
            install(name, "our-certificate")
            val marked = packContext(name, GeneratedPackIdentity.MARKER)
            assertTrue(GeneratedPackIdentity.isLocallyOwned(marked, name))
            assertFalse(GeneratedPackIdentity.hasForeignInstalledPackage(marked, name))
        }
        assertTrue(GeneratedPackIdentity.isLocallyOwned(packContext(firstName, GeneratedPackIdentity.MARKER), firstName))
        install(firstName, "replacement-certificate")
        assertFalse(GeneratedPackIdentity.isLocallyOwned(packContext(firstName, GeneratedPackIdentity.MARKER), firstName))
    }

    @Test
    fun uninstalledNameDoesNotConflict() {
        assertFalse(GeneratedPackIdentity.hasForeignInstalledPackage(context, "example.not.installed"))
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String, signer: String) = PackageInfo().apply {
        packageName = name
        signatures = arrayOf(Signature(signer.toByteArray()))
    }

    private fun install(name: String, signer: String) {
        shadowOf(context.packageManager).installPackage(packageInfo(name, signer))
    }

    private fun packContext(packageName: String, marker: String): Context {
        val archive: File = files.newFile()
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("assets/${GeneratedPackIdentity.ASSET_NAME}"))
            it.write(marker.toByteArray(Charsets.UTF_8))
            it.closeEntry()
        }
        val assets = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, archive.absolutePath)
        managers += assets
        val packageContext = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = assets
        }
        return object : ContextWrapper(context) {
            override fun createPackageContext(name: String, flags: Int): Context =
                if (name == packageName) packageContext else super.createPackageContext(name, flags)
        }
    }
}
