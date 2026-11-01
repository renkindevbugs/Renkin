package dev.renkinProject.renkin.data

import android.app.Application
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class UnsavedIconJournalStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = UnsavedIconJournalStore(context)

    private fun row(name: String, input: String = "") = DbApplication(
        "com.$name", "com.$name.Main", isAdaptiveIcon = true, isXml = false, drawable = "drawable-$name",
        calendarEnabled = true, calendarPrefix = "day_", calendarPackName = "pack", sourcePackName = "source",
        profileId = 7, sourceDrawableName = "ref", isCustomIcon = true, isLegacyIcon = true,
        baseDrawable = "base-$name", baseIsAdaptiveIcon = true, baseIsXml = true, sourceUrl = "https://example.com",
        isFallbackIcon = true, modifierRecipe = "recipe", modifierInput = input
    )

    private fun journal(instance: String, profileId: Long = 7, vararg names: String) = UnsavedIconJournal(
        instance, profileId, names.map { UnsavedIconJournalEntry("com.$it/com.$it.Main", row(it).copy(profileId = profileId)) }
    )

    @Before fun clear() {
        File(context.noBackupFilesDir, "unsaved-journal").deleteRecursively()
        File(context.noBackupFilesDir, "unsaved-session.json").delete()
    }

    @After fun resetSyncHook() { BackupOsShadow.beforeDirectorySync = null }

    @Test fun journalDirectoryIsSyncedAfterTheJournalIsPublished() = runBlocking {
        val root = File(context.noBackupFilesDir, "unsaved-journal")
        val published = mutableListOf<Boolean>()
        BackupOsShadow.beforeDirectorySync = { path ->
            if (path == root.canonicalPath || path == root.path) published += File(root, "current.json").isFile
        }

        store.writeCurrent(journal("p", 7, "a"))

        assertEquals(listOf(true), published)
    }

    @Test fun journalWhoseRenameSilentlyFailsIsReported() = runBlocking {
        // A non-empty directory in the journal's place makes AtomicFile's rename fail; it only logs that.
        File(context.noBackupFilesDir, "unsaved-journal/current.json").apply { mkdirs() }.resolve("child").writeText("x")

        val error = runCatching { store.writeCurrent(journal("p", 7, "a")) }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test fun sessionMarkerIsVerifiedAndItsDirectorySynced() = runBlocking {
        val markers = UnsavedSessionMarkerStore(context)
        val synced = mutableListOf<String>()
        BackupOsShadow.beforeDirectorySync = { synced += it }
        val marker = UnsavedSessionMarker("p", 7, editedCount = 2, refreshedCount = 1)

        markers.write(marker)

        assertEquals(marker, markers.read())
        assertTrue(synced.any { File(it).canonicalPath == context.noBackupFilesDir.canonicalPath })
    }

    @Test fun sessionMarkerWhoseRenameSilentlyFailsIsReported() = runBlocking {
        File(context.noBackupFilesDir, "unsaved-session.json").apply { mkdirs() }.resolve("child").writeText("x")

        val error = runCatching { UnsavedSessionMarkerStore(context).write(UnsavedSessionMarker("p", 7, 1, 0)) }.exceptionOrNull()

        File(context.noBackupFilesDir, "unsaved-session.json").deleteRecursively()
        assertTrue(error is IOException)
    }

    @Test fun rowCodecCoversEveryColumn() {
        val columns = DbApplication::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet() - "profileId"
        assertEquals(columns, UnsavedIconJournalCodec.encodeRow(row("a")).keys().asSequence().toSet())

        val original = row("a")
        assertEquals(original, UnsavedIconJournalCodec.decodeRow(UnsavedIconJournalCodec.encodeRow(original), 7))
    }

    @Test fun rowFromABuildWithoutNewerColumnsDecodesThemAsEntityDefaults() {
        val older = UnsavedIconJournalCodec.encodeRow(row("a"))
        val newerKeys = listOf("sourceUrl", "isFallbackIcon", "modifierRecipe", "modifierInput")
        newerKeys.forEach(older::remove)

        val decoded = UnsavedIconJournalCodec.decodeRow(older, 7)

        val defaults = DbApplication("com.a", "com.a.Main", isAdaptiveIcon = true, isXml = false, drawable = "drawable-a")
        assertEquals(
            row("a").copy(
                sourceUrl = defaults.sourceUrl, isFallbackIcon = defaults.isFallbackIcon,
                modifierRecipe = defaults.modifierRecipe, modifierInput = defaults.modifierInput
            ),
            decoded
        )
    }

    @Test fun journalFromABuildWithoutNewerColumnsIsStillOffered() = runBlocking {
        val previous = journal("old", 7, "a")
        val text = JSONObject(UnsavedIconJournalCodec.encode(previous, Int.MAX_VALUE))
        text.getJSONArray("entries").getJSONObject(0).getJSONObject("row").remove("modifierInput")
        File(context.noBackupFilesDir, "unsaved-journal").apply { mkdirs() }
            .resolve("current.json").writeText(text.toString())

        val offer = store.takeOverPrevious("new")

        assertEquals(listOf("com.a/com.a.Main"), offer?.entries?.map { it.key })
        assertEquals("", offer?.entries?.single()?.row?.modifierInput)
    }

    @Test fun rowWithoutARequiredFieldIsRejected() {
        val broken = UnsavedIconJournalCodec.encodeRow(row("a")).apply { remove("drawable") }

        assertThrows(JSONException::class.java) { UnsavedIconJournalCodec.decodeRow(broken, 7) }
    }

    @Test fun rowsBeyondTheCapAreCountedNotTruncated() {
        val full = journal("p", 7, "a", "b", "c")
        val oneRow = JSONObject().put("key", "com.a/com.a.Main").put("row", UnsavedIconJournalCodec.encodeRow(row("a")))
            .toString().length

        val decoded = UnsavedIconJournalCodec.decode(UnsavedIconJournalCodec.encode(full, oneRow + 10))

        assertEquals(listOf("com.a/com.a.Main"), decoded.entries.map { it.key })
        assertEquals(2, decoded.droppedCount)
    }

    @Test fun previousProcessJournalMovesToTheOffer() = runBlocking {
        val previous = journal("old", 7, "a")
        store.writeCurrent(previous)

        assertEquals(previous, store.takeOverPrevious("new"))
        store.writeCurrent(journal("new", 7))
        assertEquals(previous, store.takeOverPrevious("new"))
        store.discardOffer()
        assertNull(store.takeOverPrevious("new"))
    }

    @Test fun savingAnotherProfileKeepsTheOffer() = runBlocking {
        store.writeCurrent(journal("old", 7, "a"))
        store.takeOverPrevious("new")

        store.discardProfile(8)
        assertEquals(1, store.takeOverPrevious("new")!!.entries.size)
        store.discardProfile(7)
        assertNull(store.takeOverPrevious("new"))
    }

    @Test fun storageInvalidationRemovesBothJournals() = runBlocking {
        store.writeCurrent(journal("old", 7, "a"))
        store.takeOverPrevious("new")
        store.writeCurrent(journal("new", 7, "b"))

        store.invalidateAll()

        assertNull(store.takeOverPrevious("next"))
    }

    @Test fun referencedModifierInputsAreReported() = runBlocking {
        store.writeCurrent(UnsavedIconJournal("old", 7, listOf(UnsavedIconJournalEntry("com.a/com.a.Main", row("a", "doc-a")))))
        store.takeOverPrevious("new")
        store.writeCurrent(UnsavedIconJournal("new", 7, listOf(
            UnsavedIconJournalEntry("com.b/com.b.Main", row("b", "doc-b")),
            UnsavedIconJournalEntry("com.c/com.c.Main", null)
        )))

        assertEquals(setOf("doc-a", "doc-b"), store.modifierInputDocuments().toSet())
    }

    @Test fun unreadableJournalIsDropped() = runBlocking {
        File(context.noBackupFilesDir, "unsaved-journal").mkdirs()
        File(context.noBackupFilesDir, "unsaved-journal/current.json").writeText("{broken")

        assertNull(store.takeOverPrevious("new"))
    }
}
