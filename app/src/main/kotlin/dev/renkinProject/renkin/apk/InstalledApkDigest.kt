package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One instance per attempt; the immutable submitted artifact is read at most once. */
internal class InstalledApkDigest(context: Context, private val submittedApk: Uri) {
    private val appContext = context.applicationContext
    private var submittedRead = false
    private var submittedDigest: ByteArray? = null

    suspend fun matches(packageName: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            if (!submittedRead) {
                submittedDigest = try {
                    appContext.contentResolver.openInputStream(submittedApk)?.use { it.sha256() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.error("ApkInstaller", "Could not read the submitted APK for verification", error)
                    null
                }
                submittedRead = true
            }
            val expected = submittedDigest ?: return@withContext null
            @Suppress("DEPRECATION")
            val installedPath = appContext.packageManager.getApplicationInfo(packageName, 0).sourceDir
                ?: return@withContext null
            val actual = File(installedPath).inputStream().use { it.sha256() }
            MessageDigest.isEqual(expected, actual)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("ApkInstaller", "Could not verify the installed APK against the submitted artifact", error)
            null
        }
    }
}

private suspend fun InputStream.sha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) return digest.digest()
        if (count > 0) digest.update(buffer, 0, count)
    }
}
