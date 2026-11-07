package dev.renkinProject.renkin.data.online

import android.app.Application
import dev.renkinProject.renkin.data.online.OnlineIconRepository.Companion.HttpTextResponse
import java.io.IOException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class OnlineIconLoadingTest {
    private fun TestScope.testRepository(
        request: suspend (String, Int) -> HttpTextResponse
    ) = OnlineIconRepository(
        RuntimeEnvironment.getApplication(), request,
        { testScheduler.currentTime }, StandardTestDispatcher(testScheduler)
    )

    private fun searchResponse() = HttpTextResponse(body = """{"icons":[]}""", status = 200)

    private fun batchResponse(url: String, missing: Set<String> = emptySet()): HttpTextResponse {
        val names = url.substringAfter("?icons=").split(',')
        val icons = names.filterNot { it in missing }
            .joinToString(",") { "\"$it\":{\"body\":\"$BODY\"}" }
        val notFound = names.filter { it in missing }.joinToString(",") { "\"$it\"" }
        return HttpTextResponse(
            body = """{"prefix":"test","width":1,"height":1,"icons":{$icons},"not_found":[$notFound]}""",
            status = 200
        )
    }

    private fun isBatch(url: String) = url.contains(".json?icons=")

    private fun staleCollections(): File = File(
        RuntimeEnvironment.getApplication().cacheDir, "online-icons/iconify-collections.json"
    ).apply {
        parentFile!!.mkdirs()
        writeText(collectionsJson("Old"))
        assertTrue(setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000))
    }

    private fun collectionsJson(name: String) =
        """{"test":{"name":"$name","total":1,"license":{"title":"MIT"},"samples":["icon"]}}"""

    @Test
    fun staleIndexReturnsWhileRefreshIsBlockedAndThenUpdatesMemoryAndDisk() = runTest {
        val cache = staleCollections()
        val response = CompletableDeferred<HttpTextResponse>()
        var calls = 0
        val repository = testRepository { _, _ -> calls++; response.await() }

        val first = async { repository.collections() }
        runCurrent()
        assertTrue(first.isCompleted)
        assertEquals("Old", first.await()!!.single().name)
        assertEquals("Old", repository.collections()!!.single().name)
        assertEquals(1, calls)

        response.complete(HttpTextResponse(body = collectionsJson("New"), status = 200))
        advanceUntilIdle()
        assertEquals("New", repository.collections()!!.single().name)
        assertEquals(collectionsJson("New"), cache.readText())
        assertEquals(1, calls)
    }

    @Test
    fun malformedBackgroundRefreshKeepsTheUsableStaleIndex() = runTest {
        val cache = staleCollections()
        val original = cache.readText()
        var calls = 0
        val repository = testRepository { _, _ ->
            calls++
            HttpTextResponse(body = "not-json", status = 200)
        }

        assertEquals("Old", repository.collections()!!.single().name)
        advanceUntilIdle()
        assertEquals("Old", repository.collections()!!.single().name)
        assertEquals(original, cache.readText())
        assertEquals(1, calls)
    }

    @Test
    fun startsAreSpacedAcrossDifferentRequests() = runTest {
        val starts = mutableListOf<Long>()
        val repository = testRepository { _, _ ->
            starts += testScheduler.currentTime
            searchResponse()
        }
        val requests = (0 until 4).map { async { repository.search("query-$it") } }
        advanceUntilIdle()
        requests.forEach { assertEquals(emptyList<OnlineIcon>(), it.await()) }
        assertEquals(listOf(0L, 300L, 600L, 900L), starts)
    }

    @Test
    fun rateLimitPausesOtherIconsAndRetriesWithoutChangingHost() = runTest {
        val calls = mutableListOf<Pair<Long, String>>()
        val repository = testRepository { url, _ ->
            calls += testScheduler.currentTime to url
            if (calls.size == 1) HttpTextResponse(status = 429, retryable = true, retryAfterMs = 120_000)
            else searchResponse()
        }
        val first = async { repository.search("first") }
        runCurrent()
        val second = async { repository.search("second") }
        advanceTimeBy(119_999)
        runCurrent()
        assertEquals(1, calls.size)
        assertEquals(120_000L, repository.rateLimitUntil.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls.size)
        advanceUntilIdle()
        first.await()
        second.await()
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.second.startsWith("https://api.iconify.design/") })
        assertTrue(calls.drop(1).all { it.first >= 120_000 })
    }

    @Test
    fun absentRetryAfterUsesOneMinuteAndLongDelaysAreBounded() = runTest {
        for ((retryAfter, expected) in listOf(null to 60_000L, 3_600_000L to 300_000L)) {
            val repository = testRepository { _, _ ->
                HttpTextResponse(status = 429, retryable = true, retryAfterMs = retryAfter)
            }
            val startedAt = testScheduler.currentTime
            val pending = backgroundScope.launch { repository.search("limited") }
            runCurrent()
            assertEquals(startedAt + expected, repository.rateLimitUntil.value)
            pending.cancel()
            runCurrent()
        }
    }

    @Test
    fun retryBackoffDoesNotOccupyAllSvgSlots() = runTest {
        val calls = mutableListOf<String>()
        val repository = testRepository { url, _ ->
            calls += url
            if (url.contains("/fast.json")) batchResponse(url)
            else HttpTextResponse(status = 503, retryable = true, retryAfterMs = 10_000)
        }
        val slow = (0 until 6).map {
            backgroundScope.launch { repository.svg(OnlineIcon("slow-$it", "icon")) }
        }
        val available = async { repository.svg(OnlineIcon("fast", "available")) }
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(available.isCompleted)
        assertEquals(SVG, available.await())
        assertEquals(7, calls.size)
        slow.forEach { it.cancel() }
        runCurrent()
    }

    @Test
    fun cancellationReleasesAnActiveSvgSlotWithoutRetrying() = runTest {
        val calls = mutableListOf<String>()
        val blocked = CompletableDeferred<Unit>()
        val repository = testRepository { url, _ ->
            calls += url
            blocked.await()
            batchResponse(url)
        }
        val requests = (0 until 7).map {
            backgroundScope.launch { repository.svg(OnlineIcon("blocked-$it", "icon")) }
        }
        advanceTimeBy(1_900)
        runCurrent()
        assertEquals(6, calls.size)
        requests.first().cancel()
        runCurrent()
        assertEquals(7, calls.size)
        assertEquals(1, calls.count { it.contains("/blocked-0.json") })
        requests.forEach { it.cancel() }
        runCurrent()
    }

    @Test
    fun cancelledCooldownWaitDoesNotSendARequestLater() = runTest {
        var calls = 0
        val repository = testRepository { _, _ ->
            calls++
            if (calls == 1) HttpTextResponse(status = 429, retryable = true)
            else searchResponse()
        }
        val first = backgroundScope.launch { repository.search("first") }
        runCurrent()
        val waiting = backgroundScope.launch { repository.search("waiting") }
        runCurrent()
        first.cancel()
        waiting.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(emptyList<OnlineIcon>(), repository.search("new"))
        assertEquals(2, calls)
    }

    @Test
    fun memoryAndDiskCacheRemainAvailableDuringRateLimit() = runTest {
        val icon = OnlineIcon("test", "cached")
        var calls = 0
        val repository = testRepository { url, _ ->
            calls++
            if (isBatch(url)) batchResponse(url)
            else HttpTextResponse(status = 429, retryable = true)
        }
        assertEquals(SVG, repository.svg(icon))
        val limited = backgroundScope.launch { repository.search("limited") }
        advanceTimeBy(300)
        runCurrent()
        assertTrue(repository.rateLimitUntil.value > testScheduler.currentTime)
        val beforeCache = testScheduler.currentTime
        assertEquals(SVG, repository.svg(icon, cachedOnly = true))
        val coldRepository = testRepository { _, _ -> error("Cached lookup must not use the network") }
        assertEquals(SVG, coldRepository.svg(icon, cachedOnly = true))
        assertNull(coldRepository.svg(OnlineIcon("test", "missing"), cachedOnly = true))
        assertEquals(beforeCache, testScheduler.currentTime)
        assertEquals(2, calls)
        limited.cancel()
        runCurrent()
    }

    @Test
    fun transientTransportFailuresUseOfficialMirrorsAndRememberSuccessfulHost() = runTest {
        val urls = mutableListOf<String>()
        val repository = testRepository { url, _ ->
            urls += url
            if (urls.size < 3) throw IOException("Simulated transport failure")
            searchResponse()
        }
        assertEquals(emptyList<OnlineIcon>(), repository.search("first"))
        assertEquals(emptyList<OnlineIcon>(), repository.search("second"))
        assertTrue(urls[0].startsWith("https://api.iconify.design/"))
        assertTrue(urls[1].startsWith("https://api.simplesvg.com/"))
        assertTrue(urls[2].startsWith("https://api.unisvg.com/"))
        assertTrue(urls[3].startsWith("https://api.unisvg.com/"))
    }

    @Test
    fun permanentHttpFailureDoesNotRetryOrSwitchHost() = runTest {
        val urls = mutableListOf<String>()
        val repository = testRepository { url, _ ->
            urls += url
            HttpTextResponse(status = 404)
        }
        assertNull(repository.svg(OnlineIcon("test", "not-found")))
        assertEquals(1, urls.size)
        assertFalse(urls.single().contains("simplesvg"))
    }

    @Test
    fun iconsOfOneSetRequestedTogetherShareOneSortedBatch() = runTest {
        val urls = mutableListOf<String>()
        val repository = testRepository { url, _ ->
            urls += url
            batchResponse(url, missing = setOf("gone"))
        }
        val names = listOf("delta", "alpha", "gone", "charlie")
        val results = names.map { name -> async { repository.svg(OnlineIcon("test", name)) } }
        advanceUntilIdle()

        assertEquals(listOf("https://api.iconify.design/test.json?icons=alpha,charlie,delta,gone"), urls)
        assertEquals(listOf(SVG, SVG, null, SVG), results.map { it.await() })
        val cold = testRepository { _, _ -> error("Batched icons must be cached per icon") }
        assertEquals(SVG, cold.svg(OnlineIcon("test", "alpha"), cachedOnly = true))
    }

    @Test
    fun namesQueuedDuringThrottleJoinTheNextBatchAndFailuresResolveToNull() = runTest {
        val urls = mutableListOf<String>()
        val repository = testRepository { url, _ ->
            urls += url
            if (url.contains("/broken.json")) HttpTextResponse(status = 403) else batchResponse(url)
        }
        val first = async { repository.svg(OnlineIcon("test", "first")) }
        advanceTimeBy(60)
        runCurrent()
        val later = mutableListOf(async { repository.svg(OnlineIcon("test", "later-0")) })
        // The next flight is now waiting for the 300 ms slot; names queued meanwhile still join it.
        advanceTimeBy(140)
        runCurrent()
        later += (1 until 3).map { async { repository.svg(OnlineIcon("test", "later-$it")) } }
        val broken = async { repository.svg(OnlineIcon("broken", "icon")) }
        advanceUntilIdle()

        assertEquals(SVG, first.await())
        later.forEach { assertEquals(SVG, it.await()) }
        assertNull(broken.await())
        assertEquals(3, urls.size)
        assertTrue(urls.contains("https://api.iconify.design/test.json?icons=later-0,later-1,later-2"))
        assertEquals(1, urls.count { it.contains("/broken.json") })
    }

    private companion object {
        const val BODY = "<path d=\\\"M0 0L1 1\\\"/>"
        const val SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1em\" height=\"1em\" " +
            "viewBox=\"0 0 1 1\"><path d=\"M0 0L1 1\"/></svg>"
    }
}
