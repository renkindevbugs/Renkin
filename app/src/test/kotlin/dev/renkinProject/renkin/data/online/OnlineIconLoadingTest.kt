package dev.renkinProject.renkin.data.online

import android.app.Application
import dev.renkinProject.renkin.data.online.OnlineIconRepository.Companion.HttpTextResponse
import java.io.IOException
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
            if (url.endsWith("/available.svg")) HttpTextResponse(body = SVG, status = 200)
            else HttpTextResponse(status = 503, retryable = true, retryAfterMs = 10_000)
        }
        val slow = (0 until 6).map {
            backgroundScope.launch { repository.svg(OnlineIcon("test", "slow-$it")) }
        }
        val available = async { repository.svg(OnlineIcon("test", "available")) }
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
            HttpTextResponse(body = SVG, status = 200)
        }
        val requests = (0 until 7).map {
            backgroundScope.launch { repository.svg(OnlineIcon("test", "blocked-$it")) }
        }
        advanceTimeBy(1_800)
        runCurrent()
        assertEquals(6, calls.size)
        requests.first().cancel()
        runCurrent()
        assertEquals(7, calls.size)
        assertEquals(1, calls.count { it.endsWith("/blocked-0.svg") })
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
            if (url.endsWith(".svg")) HttpTextResponse(body = SVG, status = 200)
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

    private companion object {
        const val SVG = "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0L1 1\"/></svg>"
    }
}
