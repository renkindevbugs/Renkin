package dev.renkinProject.renkin.data.editor

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class EditorDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: EditorDraftStore
    private val owner = EditorDraftOwner(3, "dev.example", "dev.example.Main", "a".repeat(64), "b".repeat(64))
    private val payloads get() = mapOf("controls" to "{}".toByteArray(), "source" to ByteArray(8193) { it.toByte() })

    @Before fun setup() {
        val base = temporary.newFolder("private")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = base
        }
        root = File(base, "editor-draft")
        store = EditorDraftStore(context)
    }

    @After fun cleanup() { BackupOsShadow.beforeDirectorySync = null }

    private fun blob(candidate: EditorDraftCandidate, name: String) = File(root, "${candidate.record.id}-$name.blob")
    private suspend fun save() = store.save(store.checkpoint(), owner, payloads)

    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected draft I/O rejection") } catch (_: IOException) { }
    }

    @Test fun firstReadInitializesPersistentGenerationOutsideCallerThread() = runBlocking {
        assertFalse(root.exists())
        val callerThread = Thread.currentThread()
        val syncThreads = mutableListOf<Thread>()
        BackupOsShadow.beforeDirectorySync = { syncThreads += Thread.currentThread() }
        val checkpoint = store.checkpoint()
        assertTrue(syncThreads.isNotEmpty())
        assertTrue(syncThreads.all { it !== callerThread })
        assertTrue(File(root, "catalog.json").isFile)
        assertNull(store.candidate())
        assertEquals(checkpoint, EditorDraftStore(context).checkpoint())
    }

    @Test fun saveAndReopenPreservesEveryByteAndMetadata() = runBlocking {
        val saved = save()
        val reopened = EditorDraftStore(context)
        assertEquals(saved, reopened.candidate())
        payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, reopened.artifact(saved.checkpoint, name)) }
        assertEquals(owner, saved.record.owner)
    }

    @Test fun newerSaveRejectsOldWriteReadAndDiscard() = runBlocking {
        val first = save()
        val second = store.save(first.checkpoint, owner, mapOf("controls" to "new".toByteArray()))
        rejects { store.save(first.checkpoint, owner, payloads) }
        rejects { store.artifact(first.checkpoint, "source") }
        assertFalse(store.discard(first.checkpoint))
        assertEquals(second, store.candidate())
        assertFalse(blob(first, "source").exists())
    }

    @Test fun saveDiscardCycleCannotReviveAnEarlierEmptyCheckpoint() = runBlocking {
        val empty = store.checkpoint()
        val saved = store.save(empty, owner, payloads)
        assertTrue(store.discard(saved.checkpoint))
        assertNull(store.candidate())
        assertNotEquals(empty.storageGeneration, store.checkpoint().storageGeneration)
        rejects { store.save(empty, owner, payloads) }
        assertFalse(blob(saved, "source").exists())
    }

    @Test fun storageReplacementRejectsAllPreviouslyCapturedCheckpoints() = runBlocking {
        val saved = save()
        store.invalidateStorage()
        rejects { store.save(saved.checkpoint, owner, payloads) }
        assertNull(store.candidate())
        assertNotEquals(saved.checkpoint.storageGeneration, store.checkpoint().storageGeneration)
    }

    @Test fun competingWritersHaveExactlyOneWinner() = runBlocking {
        val expected = store.checkpoint()
        val results = (1..8).map {
            async(Dispatchers.Default) {
                try { store.save(expected, owner, payloads); true } catch (_: EditorDraftChangedException) { false }
            }
        }.awaitAll()
        assertEquals(1, results.count { it })
        assertNotNull(store.candidate())
    }

    @Test fun checksumAndSizeCorruptionAreReportedAndPreserved() = runBlocking {
        val saved = save()
        val source = blob(saved, "source")
        source.writeBytes(ByteArray(8193))
        rejects { store.candidate() }
        rejects { store.artifact(saved.checkpoint, "source") }
        assertTrue(source.exists())
        source.writeText("truncated")
        rejects { store.candidate() }
        source.delete()
        rejects { store.candidate() }
    }

    @Test fun corruptCatalogBlocksWritesUntilExplicitInvalidation() = runBlocking {
        val saved = save()
        val file = File(root, "catalog.json")
        file.writeText("{broken")
        rejects { store.checkpoint() }
        rejects { store.save(saved.checkpoint, owner, payloads) }
        assertEquals("{broken", file.readText())
        assertTrue(blob(saved, "source").exists())
        store.invalidateStorage()
        assertNull(store.candidate())
        assertNotEquals(saved.checkpoint.storageGeneration, store.checkpoint().storageGeneration)
        assertNotNull(save())
    }

    @Test fun metadataByteAndUtf8LimitsAreCheckedBeforeDecoding() = runBlocking {
        store.checkpoint()
        val file = File(root, "catalog.json")
        file.writeBytes(ByteArray(EditorDraftCodec.MAX_METADATA_BYTES + 1))
        rejects { store.checkpoint() }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        rejects { store.checkpoint() }
    }

    @Test fun invalidSlotsAreRejectedBeforeWritingAnyPayloadOrEscapingTheRoot() = runBlocking {
        val saved = save()
        val existing = root.listFiles()!!.map { it.name }.toSet()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(saved.checkpoint, owner, payloads + ("../../escape" to byteArrayOf(1))) }
        }
        assertEquals(existing, root.listFiles()!!.map { it.name }.toSet())
        assertEquals(saved, store.candidate())
        rejects { store.artifact(saved.checkpoint, "../source") }
    }

    @Test fun emptyCatalogPrunesAbandonedPayloadsWithoutOfferingThemAsDrafts() = runBlocking {
        val expected = store.checkpoint()
        val abandoned = File(root, "${UUID.randomUUID()}-controls.blob").apply { writeText("incomplete") }
        assertNull(store.candidate())
        assertFalse(abandoned.exists())
        assertEquals(expected, store.checkpoint())
    }

    @Test fun cleanupOnlyRemovesUnreferencedOwnedRegularFiles() = runBlocking {
        val saved = save()
        val orphan = File(root, "${UUID.randomUUID()}-source.blob").apply { writeText("orphan") }
        val unrelated = File(root, "notes.txt").apply { writeText("keep") }
        val directory = File(root, "${UUID.randomUUID()}-source.blob").apply { mkdir() }
        val child = File(directory, "keep").apply { writeText("nested") }
        assertEquals(saved, store.candidate())
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
        assertTrue(child.exists())
        assertTrue(blob(saved, "source").exists())
    }

    @Test fun failedPublicationLeavesPreviousDraftReadableAndPrunesOrphans() = runBlocking {
        val saved = save()
        BackupOsShadow.beforeDirectorySync = { throw IOException("Injected directory fsync failure") }
        rejects { store.save(saved.checkpoint, owner, mapOf("controls" to "replacement".toByteArray())) }
        BackupOsShadow.beforeDirectorySync = null
        assertEquals(saved, store.candidate())
        assertEquals(saved.record.artifacts.size, root.listFiles()!!.count { it.name.endsWith(".blob") })
    }

    @Test fun errorAfterRenameKeepsPublishedBlobsAndRequiresFreshCheckpoint() = runBlocking {
        val saved = save()
        var rootSyncs = 0
        BackupOsShadow.beforeDirectorySync = { path ->
            if (path == root.canonicalPath && ++rootSyncs == 2) throw IOException("Injected final fsync failure")
        }
        rejects { store.save(saved.checkpoint, owner, mapOf("controls" to "replacement".toByteArray())) }
        BackupOsShadow.beforeDirectorySync = null
        val published = checkNotNull(store.candidate())
        assertNotEquals(saved.checkpoint, published.checkpoint)
        assertArrayEquals("replacement".toByteArray(), store.artifact(published.checkpoint, "controls"))
    }

    @Test fun cancellationDuringPublicationFinishesACompleteReadableCheckpoint() = runBlocking {
        val previous = save()
        val publishing = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        BackupOsShadow.beforeDirectorySync = {
            publishing.complete(Unit)
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        val saving = launch(Dispatchers.Default) { store.save(previous.checkpoint, owner, payloads) }
        try {
            withTimeout(5_000) { publishing.await() }
            saving.cancel()
        } finally { release.countDown() }
        saving.cancelAndJoin()
        BackupOsShadow.beforeDirectorySync = null
        val published = checkNotNull(store.candidate())
        assertNotEquals(previous.checkpoint, published.checkpoint)
        assertArrayEquals(payloads.getValue("source"), store.artifact(published.checkpoint, "source"))
    }
}
