package dev.renkinProject.renkin.data.online

import android.app.Application
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DownloadedIconSetsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val iconsJson =
        """{"prefix":"demo","icons":{"dot":{"body":"<path d=\"M0 0h1v1H0z\"/>"}},"width":24,"height":24}"""
            .toByteArray()
    private val infoJson = """{"name":"Demo","total":1,"license":{"title":"MIT"},"category":"General"}""".toByteArray()

    private val responses = mutableMapOf<String, Pair<Int, ByteArray>>()
    private val requested = mutableListOf<String>()
    private val root by lazy { temporary.newFolder("online-icon-sets") }

    private fun TestScope.sets(
        transport: suspend (String, Long, OutputStream, (Long) -> Unit) -> Int? = { url, _, sink, onBytes ->
            requested += url
            val (status, body) = responses[url] ?: (HttpURLConnection.HTTP_NOT_FOUND to ByteArray(0))
            if (status == HttpURLConnection.HTTP_OK) {
                sink.write(body)
                onBytes(body.size.toLong())
            }
            status
        }
    ): DownloadedIconSets {
        return DownloadedIconSets({ root }, transport, StandardTestDispatcher(testScheduler))
    }

    private fun sha256(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun release(icons: ByteArray = iconsJson, sha: String = sha256(icons)) =
        IconSetRelease("demo", "1.0.0", sha, icons.size.toLong(), sha256(infoJson))

    private fun serveRelease(icons: ByteArray = iconsJson) {
        responses["$CDN/demo@1.0.0/icons.json"] = HttpURLConnection.HTTP_OK to icons
        responses["$CDN/demo@1.0.0/info.json"] = HttpURLConnection.HTTP_OK to infoJson
    }

    @Test
    fun latestReleaseReadsVersionHashAndSizeFromJsdelivr() = runTest {
        responses["$DATA/demo/resolved"] = HttpURLConnection.HTTP_OK to """{"version":"1.0.0"}""".toByteArray()
        responses["$DATA/demo@1.0.0?structure=flat"] = HttpURLConnection.HTTP_OK to """
            {"files":[
              {"name":"/icons.json","hash":"${sha256(iconsJson)}","size":${iconsJson.size}},
              {"name":"/info.json","hash":"${sha256(infoJson)}","size":${infoJson.size}}
            ]}
        """.trimIndent().toByteArray()

        val lookup = sets().latestRelease("demo")

        assertEquals(IconSetReleaseLookup.Found(release()), lookup)
    }

    @Test
    fun aSetWithoutAnNpmPackageIsNotAvailable() = runTest {
        assertEquals(IconSetReleaseLookup.NotAvailable, sets().latestRelease("demo"))
        assertEquals(IconSetReleaseLookup.NotAvailable, sets().latestRelease("../escape"))
    }

    @Test
    fun aCheckedDownloadIsKeptListedAndServedOffline() = runTest {
        serveRelease()
        val sets = sets()

        assertEquals(IconSetDownloadResult.Done, sets.download(release()) {})

        assertEquals(setOf("demo"), sets.sets.value.keys)
        assertTrue(sets.set("demo")!!.lookup("dot") is IconLookup.Markup)
        assertEquals(
            setOf("icons-1.0.0.json", "info-1.0.0.json", "manifest.json"),
            File(root, "demo").list()!!.toSet()
        )
        val reopened = sets()
        reopened.ensureScanned()
        assertEquals(setOf("demo"), reopened.sets.value.keys)
        assertEquals("Demo", reopened.collections().single().name)
    }

    @Test
    fun aHashMismatchKeepsNothing() = runTest {
        serveRelease()
        val sets = sets()

        val result = sets.download(release(sha = sha256("other".toByteArray()))) {}

        assertEquals(IconSetDownloadResult.IntegrityFailed, result)
        assertTrue(sets.sets.value.isEmpty())
        assertFalse(File(root, "demo/manifest.json").exists())
        assertTrue(File(root, "demo").list().orEmpty().isEmpty())
    }

    @Test
    fun anOversizedTransferIsRejected() = runTest {
        val sets = sets(transport = { _, _, _, _ -> -1 })

        assertEquals(IconSetDownloadResult.IntegrityFailed, sets.download(release()) {})
        assertTrue(sets.sets.value.isEmpty())
    }

    @Test
    fun aMissingPackageReportsNotAvailableAndANetworkErrorReportsFailed() = runTest {
        assertEquals(IconSetDownloadResult.NotAvailable, sets().download(release()) {})
        val offline = sets(transport = { _, _, _, _ -> throw java.io.IOException("offline") })
        assertEquals(IconSetDownloadResult.Failed, offline.download(release()) {})
    }

    @Test
    fun cancellingADownloadDeletesThePartialFile() = runTest {
        val started = CompletableDeferred<Unit>()
        val sets = sets(transport = { _, _, sink, _ ->
            sink.write(iconsJson, 0, 10)
            sink.flush()
            started.complete(Unit)
            awaitCancellation()
        })

        val job = launch { sets.download(release()) {} }
        runCurrent()
        started.await()
        assertTrue(File(root, "demo/icons-1.0.0.json.part").exists())
        job.cancel()
        runCurrent()

        assertTrue(File(root, "demo").list().orEmpty().isEmpty())
        assertTrue(sets.sets.value.isEmpty())
    }

    @Test
    fun aFolderLeftWithoutAValidManifestIsCleanedUpOnScan() = runTest {
        File(root, "broken").apply { mkdirs() }.resolve("icons-1.0.0.json.part").writeText("{")
        serveRelease()
        sets().download(release()) {}
        File(root, "demo/icons-0.9.0.json").writeText("stale")

        val sets = sets()
        sets.ensureScanned()

        assertEquals(setOf("demo"), sets.sets.value.keys)
        assertFalse(File(root, "broken").exists())
        assertFalse(File(root, "demo/icons-0.9.0.json").exists())
    }

    @Test
    fun deleteRemovesTheSetAndItsFiles() = runTest {
        serveRelease()
        val sets = sets()
        sets.download(release()) {}

        sets.delete("demo")

        assertTrue(sets.sets.value.isEmpty())
        assertFalse(File(root, "demo").exists())
        assertNull(sets.set("demo"))
    }

    private companion object {
        const val CDN = "https://cdn.jsdelivr.net/npm/@iconify-json"
        const val DATA = "https://data.jsdelivr.com/v1/packages/npm/@iconify-json"
    }
}
