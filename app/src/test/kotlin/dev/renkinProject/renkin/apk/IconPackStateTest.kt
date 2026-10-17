package dev.renkinProject.renkin.apk

import android.app.Application
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.packages.IconPackCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconPackStateTest {
    private val pack = IconPack("com.pack", "Pack", 1, "1", 0)
    private val other = IconPack("com.other", "Other", 1, "1", 0)
    private fun repository(): IconPackRepository {
        val context = RuntimeEnvironment.getApplication()
        return IconPackRepository(context, IconPackCatalog(context))
    }

    @Test fun publicLoadUsesInstalledCatalogAndCompletesWithoutExternalPacks() = runBlocking {
        val repo = repository()
        repo.load(emptyList())
        assertEquals(IconPackState(emptyList(), true), repo.state.value)
    }

    @Test fun ownPacksOfEveryProfileAndThePreRenameNameAreNeverIconSources() = runBlocking {
        val repo = repository()
        val own = listOf(
            IconPack("dev.renkinProject.renkinpack", "Renkin", 1, "1", 0),
            IconPack("dev.renkinProject.renkinpack.p4", "Renkin profile", 1, "1", 0),
            IconPack("dev.alembiconsProject.renkinpack", "Renkin legacy", 1, "1", 0)
        )

        repo.load(emptyList(), { own + pack }) { _, _ -> emptyList() }

        assertEquals(listOf(pack), repo.state.value.packs)
    }

    @Test fun discoveryIsVisibleBeforeReadinessAndReadyIncludesCompletedIndexes() = runBlocking {
        val repo = repository()
        assertEquals(IconPackState(), repo.state.value)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val apps = listOf(InstalledApplication("com.app", "com.app.Main", 0))
        val load = async {
            repo.load(apps, { listOf(pack) }) { _, supplied ->
                assertEquals(apps, supplied)
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                listOf(RawItem("ComponentInfo{com.app/com.app.Main}", "app"),
                    RawCalendar("ComponentInfo{com.app/com.app.Main}", "calendar_"))
            }
        }
        try {
            withTimeout(5000) { entered.await() }
            assertEquals(IconPackState(listOf(pack), false), repo.state.value)
            assertFalse(repo.iconPackLoaded)
        } finally { release.countDown() }
        withTimeout(5000) { load.await() }
        assertTrue(repo.state.value.loaded)
        assertEquals(listOf(pack), repo.iconPacks)
        assertEquals(setOf("calendar_"), repo.calendarPrefixes(pack.packageName))
        assertEquals(mapOf(pack.packageName to 1), repo.matchedAppCounts())
    }

    @Test fun emptyDiscoveryCompletesAndOwnGeneratedPacksAreExcluded() = runBlocking {
        val repo = repository()
        repo.load(emptyList(), { emptyList() }) { _, _ -> fail("No pack should be parsed"); emptyList() }
        assertEquals(IconPackState(emptyList(), true), repo.state.value)
        repo.load(emptyList(), { listOf(pack, pack.copy(packageName = IconPackBuilder.PACKAGE_NAME),
            pack.copy(packageName = IconPackBuilder.PACKAGE_NAME + ".p2")) }) { parsed, _ ->
            assertEquals(pack, parsed)
            emptyList()
        }
        assertEquals(IconPackState(listOf(pack), true), repo.state.value)
    }

    @Test fun lateCollectorsReceiveCompletedStateAndReloadUsesNewSnapshot() = runBlocking {
        val repo = repository()
        repo.load(emptyList(), { listOf(pack) }) { _, _ -> emptyList() }
        assertEquals(IconPackState(listOf(pack), true), repo.state.first())
        repo.load(emptyList(), { listOf(other) }) { _, _ -> emptyList() }
        assertEquals(IconPackState(listOf(other), true), repo.state.first())
        assertEquals(repo.state.value.loaded, repo.iconPackLoaded)
    }

    @Test fun discoveryFailureLeavesReloadNotReadyAndRetryCanReplaceOldPacks() = runBlocking {
        val repo = repository()
        repo.load(emptyList(), { listOf(pack) }) { _, _ -> emptyList() }
        val failure = IOException("Discovery failed")
        val reported = runCatching {
            repo.load(emptyList(), { throw failure }) { _, _ -> emptyList() }
        }.exceptionOrNull()
        assertTrue(reported is IOException)
        assertEquals(failure.message, reported?.message)
        assertEquals(IconPackState(listOf(pack), false), repo.state.value)
        repo.load(emptyList(), { listOf(other) }) { _, _ -> emptyList() }
        assertEquals(IconPackState(listOf(other), true), repo.state.value)
    }

    @Test fun malformedPackDoesNotPreventReadinessOrHealthyIndexes() = runBlocking {
        val repo = repository()
        repo.load(emptyList(), { listOf(pack, other) }) { parsed, _ ->
            if (parsed == pack) throw IOException("Malformed appfilter")
            listOf(RawCalendar("ComponentInfo{com.app/com.app.Main}", "healthy_"))
        }
        assertTrue(repo.state.value.loaded)
        assertTrue(repo.calendarPrefixes(pack.packageName).isEmpty())
        assertEquals(setOf("healthy_"), repo.calendarPrefixes(other.packageName))
    }

    @Test fun cancelledParserPropagatesWithoutPublishingReadyAndNextLoadCanSucceed() = runBlocking {
        val repo = repository()
        val cancelled = async {
            repo.load(emptyList(), { listOf(pack) }) { _, _ -> throw CancellationException("Cancelled parser") }
        }
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        assertTrue(cancelled.isCancelled)
        assertFalse(repo.state.value.loaded)
        repo.load(emptyList(), { listOf(other) }) { _, _ -> emptyList() }
        assertEquals(IconPackState(listOf(other), true), repo.state.value)
    }
}
