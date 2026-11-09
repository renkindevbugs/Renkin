package dev.renkinProject.renkin.data.online

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import dev.renkinProject.renkin.feature.online.OnlineIconBrowserViewModel
import dev.renkinProject.renkin.feature.online.OnlineIconCachedPreview
import dev.renkinProject.renkin.feature.online.OnlineIconImport
import dev.renkinProject.renkin.feature.online.OnlineIconPreview
import dev.renkinProject.renkin.data.online.OnlineIconRepository.Companion.HttpTextResponse
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DownloadedIconCacheRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun tearDown() = Dispatchers.resetMain()

    private inner class Fixture(private val scope: TestScope) {
        private val cache = temporary.newFolder()
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir() = cache
        }
        private val releases = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<String>()
        val downloads = DownloadedIconSets(
            { cache.resolve("downloaded-sets") },
            { url, _, sink, onBytes ->
                releases[url]?.let { body ->
                    sink.write(body)
                    onBytes(body.size.toLong())
                    200
                } ?: 404
            },
            StandardTestDispatcher(scope.testScheduler)
        )
        private val localSets = LocalIconSets(
            { error("These tests only read downloaded sets") },
            StandardTestDispatcher(scope.testScheduler),
            downloads
        )

        fun repository(
            response: suspend (String) -> HttpTextResponse = { HttpTextResponse(status = 404) }
        ) = OnlineIconRepository(
            context,
            { url, _ -> requests += url; response(url) },
            { scope.testScheduler.currentTime },
            StandardTestDispatcher(scope.testScheduler),
            localSets
        )

        suspend fun install(version: String, json: String) {
            val body = json.toByteArray()
            releases["https://cdn.jsdelivr.net/npm/@iconify-json/demo@$version/icons.json"] = body
            val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
            val release = IconSetRelease("demo", version, digest, body.size.toLong(), null)
            assertEquals(IconSetDownloadResult.Done, downloads.download(release) {})
        }
    }

    @Test fun updatingDownloadedSetReplacesWarmIndexMemoryAndDiskSvgForTheSameIconUrl() = runTest {
        val fixture = Fixture(this)
        val repository = fixture.repository()
        fixture.install("1.0.0", setJson(OLD_PATH, "removed"))
        assertEquals(setOf("dot", "removed"), repository.icons(COLLECTION)!!.map { it.name }.toSet())
        assertTrue(repository.svg(ICON)!!.contains(OLD_PATH))

        fixture.install("2.0.0", setJson(NEW_PATH, "added"))

        assertEquals(setOf("dot", "added"), repository.icons(COLLECTION)!!.map { it.name }.toSet())
        // A new repository has no memory entries and must not reuse v1's extracted SVG file.
        assertTrue(fixture.repository().svg(ICON, cachedOnly = true)!!.contains(NEW_PATH))
        // The already-open repository also has a warm v1 SVG in memory.
        assertTrue(repository.svg(ICON, cachedOnly = true)!!.contains(NEW_PATH))
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun deletingDownloadedSetFallsBackToNetworkInsteadOfItsOldIndexAndSvg() = runTest {
        val fixture = Fixture(this)
        val repository = fixture.repository { url ->
            when {
                "collection?prefix=demo" in url -> HttpTextResponse(
                    body = """{"prefix":"demo","uncategorized":["dot","remote"]}""", status = 200
                )
                "/demo.json?icons=" in url -> HttpTextResponse(body = setJson(NETWORK_PATH, "remote"), status = 200)
                else -> HttpTextResponse(status = 404)
            }
        }
        fixture.install("1.0.0", setJson(OLD_PATH, "local"))
        repository.icons(COLLECTION)
        assertTrue(repository.svg(ICON)!!.contains(OLD_PATH))
        assertTrue(fixture.requests.isEmpty())

        fixture.downloads.delete("demo")

        assertNull(repository.svg(ICON, cachedOnly = true))
        assertEquals(setOf("dot", "remote"), repository.icons(COLLECTION)!!.map { it.name }.toSet())
        assertTrue(repository.svg(ICON)!!.contains(NETWORK_PATH))
        assertTrue(fixture.repository().svg(ICON, cachedOnly = true)!!.contains(NETWORK_PATH))
        assertEquals(2, fixture.requests.size)
    }

    @Test fun updatingDownloadedSetReplacesParsedPreviewAndTheVectorImportedFromIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        val browser = OnlineIconBrowserViewModel(
            fixture.repository(), fixture.context, CollapsedSetCategories(fixture.context), fixture.downloads
        )
        try {
            fixture.install("1.0.0", setJson(OLD_PATH, "removed"))
            val oldPreview = browser.preview(ICON, 0xff000000.toInt()) as OnlineIconPreview.Editable
            assertEquals(OLD_PATH, oldPreview.svg.paths.single().pathData)
            assertEquals(OLD_PATH, (browser.importIcon(ICON) as OnlineIconImport.Imported).svg.paths.single().pathData)

            fixture.install("2.0.0", setJson(NEW_PATH, "added"))

            val cached = browser.cachedPreview(ICON, 0xff000000.toInt(), detail = false) as OnlineIconCachedPreview.Ready
            assertEquals(NEW_PATH, (cached.preview as OnlineIconPreview.Editable).svg.paths.single().pathData)
            assertEquals(NEW_PATH, (browser.importIcon(ICON) as OnlineIconImport.Imported).svg.paths.single().pathData)
            assertTrue(fixture.requests.isEmpty())
        } finally {
            browser.viewModelScope.cancel()
        }
    }

    private companion object {
        val COLLECTION = IconifyCollection("demo", "Demo", 2, "MIT", "General", false, emptyList())
        val ICON = OnlineIcon("demo", "dot")
        const val OLD_PATH = "M0 0h1v1H0z"
        const val NEW_PATH = "M2 2h4v4H2z"
        const val NETWORK_PATH = "M8 8h8v8H8z"

        fun setJson(path: String, otherIcon: String) =
            """{"prefix":"demo","icons":{"dot":{"body":"<path d=\"$path\"/>"},"$otherIcon":{"body":"<path d=\"M0 0h2v2H0z\"/>"}},"width":24,"height":24}"""
    }
}
