package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class InstalledApkDigestTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val packageName = "example.generated.pack"

    private fun file(name: String, bytes: ByteArray) =
        File(application.cacheDir, name).apply { writeBytes(bytes) }

    private fun installAt(source: File) {
        shadowOf(application.packageManager).installPackage(PackageInfo().apply {
            packageName = this@InstalledApkDigestTest.packageName
            applicationInfo = ApplicationInfo().apply {
                packageName = this@InstalledApkDigestTest.packageName
                sourceDir = source.absolutePath
            }
        })
    }

    @Test
    fun identicalInstalledApkMatchesTheSubmittedArtifact() = runBlocking {
        val submitted = file("submitted.apk", ByteArray(200_000) { (it % 251).toByte() })
        installAt(file("installed.apk", submitted.readBytes()))
        assertEquals(true, InstalledApkDigest(application, Uri.fromFile(submitted)).matches(packageName))
    }

    @Test
    fun differentInstalledApkDoesNotMatch() = runBlocking {
        val submitted = file("submitted.apk", byteArrayOf(1, 2, 3))
        installAt(file("installed.apk", byteArrayOf(1, 2, 4)))
        assertEquals(false, InstalledApkDigest(application, Uri.fromFile(submitted)).matches(packageName))
    }

    @Test
    fun unreadableSubmittedApkIsUnknownRatherThanAMismatch() = runBlocking {
        installAt(file("installed.apk", byteArrayOf(1)))
        val missing = Uri.fromFile(File(application.cacheDir, "missing.apk"))
        assertNull(InstalledApkDigest(application, missing).matches(packageName))
    }

    @Test
    fun missingInstalledPackageIsUnknown() = runBlocking {
        val submitted = file("submitted.apk", byteArrayOf(1))
        assertNull(InstalledApkDigest(application, Uri.fromFile(submitted)).matches(packageName))
    }

    @Test
    fun submittedArtifactIsHashedOnceForTheAttempt() = runBlocking {
        val submitted = file("submitted.apk", byteArrayOf(7, 7, 7))
        val installed = file("installed.apk", byteArrayOf(7, 7, 7))
        installAt(installed)
        val digest = InstalledApkDigest(application, Uri.fromFile(submitted))
        assertEquals(true, digest.matches(packageName))
        submitted.writeBytes(byteArrayOf(9))
        assertEquals(true, digest.matches(packageName))
        installed.writeBytes(byteArrayOf(9))
        assertEquals(false, digest.matches(packageName))
    }
}
