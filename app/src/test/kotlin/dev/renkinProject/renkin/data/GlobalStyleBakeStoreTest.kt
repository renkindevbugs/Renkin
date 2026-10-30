package dev.renkinProject.renkin.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalStyleBakeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val original = GlobalStyleBakeStore.Original(
        "pkg", "pkg.Main", base = "b".repeat(50_000), baseIsXml = false, baseIsAdaptive = true, bakedFingerprint = "abc"
    )

    private fun entry(id: Long, profileId: Long = 1, appliedAt: Long = 10) =
        GlobalStyleHistoryEntry(id = id, profileId = profileId, appliedAt = appliedAt, style = "{}")

    @Test
    fun committedOriginalsReadBackWithTheirHeader() {
        val store = GlobalStyleBakeStore(folder.root)
        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "baked"), listOf(original)), 7)

        assertEquals(GlobalStyleBakeStore.Header(1, 10, "baked"), store.header(entry(7)))
        assertEquals(listOf(original), store.originals(entry(7)))
    }

    @Test
    fun aReusedEntryIdFromAnotherProfileOrTimeIsNotThisBake() {
        val store = GlobalStyleBakeStore(folder.root)
        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "baked"), listOf(original)), 7)

        assertNull(store.header(entry(7, profileId = 2)))
        assertNull(store.originals(entry(7, appliedAt = 11)))
    }

    @Test
    fun aPendingBakeIsNeverReadAndCanBeDiscarded() {
        val store = GlobalStyleBakeStore(folder.root)
        val pending = store.writePending(GlobalStyleBakeStore.Header(1, 10, "baked"), listOf(original))

        assertNull(store.header(entry(10)))
        store.discardPending()
        assertFalse(pending.exists())
    }

    @Test
    fun pruningKeepsOnlyTheOriginalsOfExistingEntries() {
        val store = GlobalStyleBakeStore(folder.root)
        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "kept"), emptyList()), 1)
        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "gone"), emptyList()), 2)

        store.prune(setOf(1))

        assertEquals("kept", store.header(entry(1))?.bakedStyle)
        assertNull(store.header(entry(2)))
    }

    @Test
    fun aCorruptFileReadsAsNoBake() {
        val store = GlobalStyleBakeStore(folder.root)
        folder.root.resolve("3.json.gz").writeText("not gzip")

        assertNull(store.header(entry(3)))
    }
}
