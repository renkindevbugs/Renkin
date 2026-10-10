package dev.renkinProject.renkin.data.editor

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
class EditorDraftInvalidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: EditorDraftStore
    private val owner = EditorDraftOwner(3, "dev.example", "dev.example.Main", "a".repeat(64), "b".repeat(64))
    private val payloads = mapOf("controls" to byteArrayOf(1, 2, 3))
    private val catalog get() = File(root, "catalog.json")

    @Before fun setup() {
        base = temporary.newFolder("private")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = base
        }
        root = File(base, "editor-draft")
        store = EditorDraftStore(context)
    }

    @After fun cleanup() { BackupOsShadow.beforeDirectorySync = null }

    private suspend fun save() = store.save(store.checkpoint(), owner, payloads)
    private suspend fun unreadable(): EditorDraftResetLease {
        val result = store.inspectCheckpoint()
        assertTrue(result is EditorDraftInspection.Unreadable)
        return requireNotNull((result as EditorDraftInspection.Unreadable).resetLease)
    }
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected draft I/O failure") } catch (_: IOException) { }
    }

    @Test fun deletingOwningProfileRemovesDraftAndExpiresWritersAndAcceptance() = runBlocking {
        val saved = save()
        val lease = requireNotNull(store.acceptanceLease(saved.checkpoint))
        EditorDraftInvalidation(store).beforeProfileDeletion(owner.profileId)
        assertNull(store.candidate())
        assertFalse(lease.publishIfCurrent { true })
        rejects { store.save(saved.checkpoint, owner, payloads) }
        assertNotEquals(saved.checkpoint.storageGeneration, store.checkpoint().storageGeneration)
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".blob") })
    }

    @Test fun deletingOtherProfileRetainsExactDraftButRejectsOldWriter() = runBlocking {
        val saved = save()
        store.invalidateProfile(9)
        val retained = requireNotNull(store.candidate())
        assertEquals(saved.record, retained.record)
        assertNotEquals(saved.checkpoint.storageGeneration, retained.checkpoint.storageGeneration)
        assertArrayEquals(payloads.getValue("controls"), store.artifact(retained.checkpoint, "controls"))
        rejects { store.save(saved.checkpoint, owner.copy(profileId = 9), payloads) }
        assertEquals(retained, EditorDraftStore(context).candidate())
    }

    @Test fun deletingProfileFromEmptyStoreRejectsAlreadyCapturedWriter() = runBlocking {
        val empty = store.checkpoint()
        store.invalidateProfile(owner.profileId)
        rejects { store.save(empty, owner, payloads) }
        assertNull(store.candidate())
    }

    @Test fun corruptCatalogDoesNotBlockProfileDeletion() = runBlocking {
        val saved = save()
        catalog.writeText("{broken")
        store.invalidateProfile(owner.profileId)
        assertNull(store.candidate())
        assertNotEquals(saved.checkpoint.storageGeneration, store.checkpoint().storageGeneration)
    }

    @Test fun invalidUtf8DoesNotBlockProfileDeletion() = runBlocking {
        save()
        catalog.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        store.invalidateProfile(owner.profileId)
        assertNull(store.candidate())
    }

    @Test fun profileDeletionRemovesAnObstructingRootFile() = runBlocking {
        root.writeText("not a directory")
        store.invalidateProfile(owner.profileId)
        assertFalse(root.exists())
        assertNull(store.candidate())
    }

    @Test fun failedProfileRotationMayDiscardAnotherProfilesDraftAndExpiresItsLease() = runBlocking {
        val saved = save()
        val lease = requireNotNull(store.acceptanceLease(saved.checkpoint))
        BackupOsShadow.beforeDirectorySync = { throw IOException("Draft disk unavailable") }
        store.invalidateProfile(9)
        assertFalse(lease.publishIfCurrent { true })
        BackupOsShadow.beforeDirectorySync = null
        assertNull(store.candidate())
        rejects { store.save(saved.checkpoint, owner, payloads) }
    }

    @Test fun failedEmergencyRotationClearsCacheButPreservesSiblingFiles() = runBlocking {
        val saved = save()
        val sibling = File(base, "unrelated").apply { writeText("keep") }
        val lease = requireNotNull(store.acceptanceLease(saved.checkpoint))
        BackupOsShadow.beforeDirectorySync = { throw IOException("Draft fsync failure") }
        EditorDraftInvalidation(store).duringStorageRecovery()
        assertFalse(root.exists())
        assertEquals("keep", sibling.readText())
        assertFalse(lease.publishIfCurrent { true })
        BackupOsShadow.beforeDirectorySync = null
        rejects { store.save(saved.checkpoint, owner, payloads) }
        assertNull(store.candidate())
    }

    @Test fun strictReplacementFailurePropagatesBeforeRemovingTheOldDraft() = runBlocking {
        val saved = save()
        val lease = requireNotNull(store.acceptanceLease(saved.checkpoint))
        BackupOsShadow.beforeDirectorySync = { throw IOException("Draft fsync failure") }
        rejects { EditorDraftInvalidation(store).beforeStorageReplacement() }
        BackupOsShadow.beforeDirectorySync = null
        assertEquals(saved, store.candidate())
        assertFalse(lease.publishIfCurrent { true })
    }

    @Test fun failedEmergencyCleanupKeepsDraftAccessBlockedUntilEmptyRotationSucceeds() = runBlocking {
        val saved = save()
        var unavailable = true
        val failingContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File {
                if (unavailable) throw IOException("Private storage unavailable")
                return base
            }
        }
        val recovering = EditorDraftStore(failingContext)
        recovering.invalidateForRecovery()
        rejects { recovering.checkpoint() }
        assertEquals(saved, store.candidate())
        unavailable = false
        rejects { recovering.save(saved.checkpoint, owner, payloads) }
        assertNull(recovering.candidate())
        assertNotEquals(saved.checkpoint.storageGeneration, recovering.checkpoint().storageGeneration)
        assertNull(EditorDraftStore(context).candidate())
    }

    @Test fun failedProfileCleanupDoesNotThrowAndLaterRejectsTheOldCheckpoint() = runBlocking {
        val saved = save()
        var unavailable = true
        val failingContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File {
                if (unavailable) throw IOException("Private storage unavailable")
                return base
            }
        }
        val deleting = EditorDraftStore(failingContext)
        deleting.invalidateProfile(owner.profileId)
        rejects { deleting.candidate() }
        unavailable = false
        rejects { deleting.save(saved.checkpoint, owner, payloads) }
        assertNull(deleting.candidate())
    }

    @Test fun queuedWriterCannotResurrectDraftAfterInvalidation() = runBlocking {
        val saved = save()
        val publishing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        BackupOsShadow.beforeDirectorySync = {
            publishing.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
        }
        val invalidating = async(Dispatchers.Default) { store.invalidateProfile(owner.profileId) }
        try {
            withTimeout(5_000) { publishing.await() }
            val writer = async(Dispatchers.Default) {
                try { store.save(saved.checkpoint, owner, payloads); false }
                catch (_: EditorDraftChangedException) { true }
            }
            release.countDown()
            invalidating.await()
            assertTrue(writer.await())
        } finally { release.countDown() }
        BackupOsShadow.beforeDirectorySync = null
        assertNull(store.candidate())
    }

    @Test fun readableInspectionRetainsCheckpointWithoutReadingBrokenArtwork() = runBlocking {
        val saved = save()
        File(root, "${saved.record.id}-controls.blob").writeText("damaged")
        assertEquals(EditorDraftInspection.Readable(saved.checkpoint), store.inspectCheckpoint())
        rejects { store.candidate() }
    }

    @Test fun unchangedUnreadableCatalogResetsOnlyAfterExplicitAction() = runBlocking {
        val saved = save()
        catalog.writeText("{broken")
        val lease = unreadable()
        assertEquals("{broken", catalog.readText())
        assertNotNull(unreadable())
        val reset = requireNotNull(store.discardUnreadable(lease))
        assertNotEquals(saved.checkpoint.storageGeneration, reset.storageGeneration)
        assertNull(store.candidate())
        assertNull(store.discardUnreadable(lease))
    }

    @Test fun repairedCatalogCannotBeDiscardedByAnOlderError() = runBlocking {
        val saved = save()
        val original = catalog.readBytes()
        catalog.writeText("{broken")
        val lease = unreadable()
        catalog.writeBytes(original)
        assertNull(store.discardUnreadable(lease))
        assertEquals(saved, store.candidate())
    }

    @Test fun changedCorruptionRequiresANewResetConfirmation() = runBlocking {
        save()
        catalog.writeText("{broken")
        val lease = unreadable()
        catalog.writeText("{different")
        assertNull(store.discardUnreadable(lease))
        assertEquals("{different", catalog.readText())
        assertNotNull(store.discardUnreadable(unreadable()))
    }

    @Test fun resetLeaseCannotBeUsedByAnotherStore() = runBlocking {
        save()
        catalog.writeText("{broken")
        val lease = unreadable()
        assertNull(EditorDraftStore(context).discardUnreadable(lease))
        assertEquals("{broken", catalog.readText())
    }

    @Test fun admittedFailedResetExpiresItsLeaseAndRequiresReinspection() = runBlocking {
        save()
        catalog.writeText("{broken")
        val lease = unreadable()
        BackupOsShadow.beforeDirectorySync = { throw IOException("Reset failed") }
        rejects { store.discardUnreadable(lease) }
        BackupOsShadow.beforeDirectorySync = null
        assertNull(store.discardUnreadable(lease))
        assertNotNull(store.discardUnreadable(unreadable()))
        assertNull(store.candidate())
    }

    @Test fun validPublicationExpiresAnUnreadableResetLease() = runBlocking {
        save()
        catalog.writeText("{broken")
        val lease = unreadable()
        store.invalidateStorage()
        val fresh = save()
        assertNull(store.discardUnreadable(lease))
        assertEquals(fresh, store.candidate())
    }

    @Test fun newMetadataFileChangesInvalidateResetConfirmation() = runBlocking {
        save()
        catalog.writeText("{broken")
        val lease = unreadable()
        File(root, "catalog.json.new").writeText("new publication")
        assertNull(store.discardUnreadable(lease))
        assertEquals("{broken", catalog.readText())
    }

    @Test fun promotedValidBackupCannotBeDiscardedByAnOldError() = runBlocking {
        val saved = save()
        val original = catalog.readBytes()
        catalog.writeText("{broken")
        val lease = unreadable()
        File(root, "catalog.json.bak").writeBytes(original)
        assertNull(store.discardUnreadable(lease))
        assertEquals(saved, store.candidate())
    }

    @Test fun backupPromotionDuringFailedResetRequiresFreshInspection() = runBlocking {
        save()
        catalog.writeText("{broken primary")
        File(root, "catalog.json.bak").writeText("{broken backup")
        val lease = unreadable()
        // Inspection itself may promote the backup; reset must compare the resulting observation.
        assertEquals("{broken backup", catalog.readText())
        assertNotNull(store.discardUnreadable(lease))
        assertNull(store.candidate())
    }

    @Test fun oversizedMetadataCanBeExplicitlyResetWithoutWholeDocumentAllocation() = runBlocking {
        save()
        catalog.writeBytes(ByteArray(EditorDraftCodec.MAX_METADATA_BYTES + 1))
        assertNotNull(store.discardUnreadable(unreadable()))
        assertNull(store.candidate())
    }

    @Test fun inaccessibleMetadataReturnsErrorWithoutAResetLease() = runBlocking {
        root.writeText("not a directory")
        val result = store.inspectCheckpoint() as EditorDraftInspection.Unreadable
        assertTrue(result.cause is IOException)
        assertNull(result.resetLease)
        assertEquals("not a directory", root.readText())
    }
}
