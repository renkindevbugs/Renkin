package dev.renkinProject.renkin.feature.online

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import dev.renkinProject.renkin.data.online.IconifyCollection
import dev.renkinProject.renkin.data.online.OnlineIconRepository
import dev.renkinProject.renkin.data.online.OnlineIconRepository.Companion.HttpTextResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every browser session owns its loads: a closed session's late result never reaches the next one. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class OnlineIconBrowserSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun tearDown() = Dispatchers.resetMain()

    /** One suspended HTTP exchange the test answers by hand, recording whether it was cancelled. */
    private class PendingRequest(val url: String) {
        val response = CompletableDeferred<HttpTextResponse>()
        var cancelled = false
    }

    private val requests = mutableListOf<PendingRequest>()

    private fun TestScope.viewModel(): OnlineIconBrowserViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cache = temporary.newFolder("cache")
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir() = cache
        }
        val repository = OnlineIconRepository(
            context,
            { url, _ ->
                val pending = PendingRequest(url).also { requests += it }
                try {
                    pending.response.await()
                } catch (error: CancellationException) {
                    pending.cancelled = true
                    throw error
                }
            },
            { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler)
        )
        return OnlineIconBrowserViewModel(repository, context)
    }

    private fun collection(prefix: String) = IconifyCollection(prefix, prefix, 1, "MIT", "General", false, emptyList())

    private val collectionsJson = """{"aaa":{"name":"Aaa","total":1,"license":{"title":"MIT"},"samples":["x"]}}"""
    private fun setJson(prefix: String) = """{"prefix":"$prefix","uncategorized":["$prefix-icon"]}"""

    // The repository spaces request starts apart, so a load's request appears after virtual time passes.
    private fun TestScope.awaitRequest(count: Int): PendingRequest {
        repeat(600) {
            if (requests.size >= count) return requests[count - 1]
            advanceTimeBy(50)
            runCurrent()
        }
        error("request $count was never sent")
    }

    @Test fun closingCancelsTheLoadAndItsLateFailureNeverReachesTheReopenedBrowser() = runTest {
        val vm = viewModel()
        vm.beginSession()
        runCurrent()
        val first = requests.single()

        vm.endSession()
        vm.beginSession()
        runCurrent()
        first.response.complete(HttpTextResponse(status = 404))
        advanceUntilIdle()

        assertTrue(first.cancelled)
        assertFalse(vm.collectionsFailed)
        assertNull(vm.collections)

        awaitRequest(2).response.complete(HttpTextResponse(body = collectionsJson, status = 200))
        advanceUntilIdle()
        assertEquals(listOf("aaa"), vm.collections?.map { it.prefix })
        assertFalse(vm.collectionsFailed)
    }

    @Test fun openingAnotherCollectionCancelsThePreviousIconLoad() = runTest {
        val vm = viewModel()
        vm.beginSession()
        runCurrent()
        requests.single().response.complete(HttpTextResponse(body = collectionsJson, status = 200))
        advanceUntilIdle()

        vm.openCollection(collection("aaa"), 0, 0, 0)
        runCurrent()
        val iconsOfA = awaitRequest(2)
        vm.backToCollections()
        vm.openCollection(collection("bbb"), 0, 0, 0)
        runCurrent()
        iconsOfA.response.complete(HttpTextResponse(body = setJson("aaa"), status = 200))
        awaitRequest(3).response.complete(HttpTextResponse(body = setJson("bbb"), status = 200))
        advanceUntilIdle()

        assertTrue(iconsOfA.cancelled)
        assertEquals("bbb", vm.selectedCollection?.prefix)
        assertEquals(listOf("bbb"), vm.icons?.map { it.prefix }?.distinct())
    }
}
