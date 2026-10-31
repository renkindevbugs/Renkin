package dev.renkinProject.renkin.data

import android.app.Application
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    private fun GlobalStyleBakeStore.originals(entry: GlobalStyleHistoryEntry): List<GlobalStyleBakeStore.Original>? {
        val read = mutableListOf<GlobalStyleBakeStore.Original>()
        return if (runBlocking { forEachOriginal(entry) { read += it } }) read else null
    }

    @Test
    fun committedOriginalsReadBackWithTheirHeader() {
        val store = GlobalStyleBakeStore(folder.root)
        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "baked"), listOf(original)), 7)

        assertEquals(GlobalStyleBakeStore.Header(1, 10, "baked"), store.header(entry(7)))
        assertEquals(listOf(original), store.originals(entry(7)))
        assertEquals(1, store.count(entry(7)))
    }

    @Test
    fun originalsAreTakenFromTheSourceOnlyWhileWriting() {
        val store = GlobalStyleBakeStore(folder.root)
        var taken = 0
        val source = sequenceOf(original, original.copy(packageName = "other")).onEach { taken++ }.asIterable()

        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "baked"), source), 7)

        assertEquals(2, taken)
        assertEquals(2, store.count(entry(7)))
        assertEquals(listOf("pkg", "other"), store.originals(entry(7))?.map { it.packageName })
    }

    @Test
    fun aFileCutOffMidwayIsNotReadAsComplete() {
        val store = GlobalStyleBakeStore(folder.root)
        GZIPOutputStream(folder.root.resolve("4.json.gz").outputStream()).bufferedWriter().use {
            it.write("""{"version":1,"profileId":1,"appliedAt":10,"bakedStyle":"baked","icons":[{"packageName":"pkg","activityName":"a"},{"packa""")
        }

        assertEquals("baked", store.header(entry(4))?.bakedStyle)
        assertNull(store.count(entry(4)))
        assertNull(store.originals(entry(4)))
    }

    @Test
    fun failuresOfTheActionAreNotTakenForAnUnreadableFile() {
        val store = GlobalStyleBakeStore(folder.root)
        store.commitPending(store.writePending(GlobalStyleBakeStore.Header(1, 10, "baked"), listOf(original)), 7)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.forEachOriginal(entry(7)) { throw IllegalStateException("superseded") } }
        }
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
