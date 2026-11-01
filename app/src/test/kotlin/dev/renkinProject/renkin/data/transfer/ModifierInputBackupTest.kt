package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.*
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.icon.creator.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class ModifierInputBackupTest {
    @Test fun profilePackIdentityTravelsWithFullBackupAndSharedProfileWithoutExternalArtworkFiles() = runBlocking {
        seed("Pack identity")
        val icon = ProfilePackIconCodec(context).encode(
            dev.renkinProject.renkin.drawable.BitmapIconDrawable(
                android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            ), sourcePackName = "pack.logo.source"
        )
        pack.updateProfileDetails(1, "Pack identity", "", "Custom pack", "com.example.custompack", icon)
        val full = export()
        val shared = ByteArrayOutputStream().also { manager.exportProfile(1) { it } }.toByteArray()
        pack.updateProfileDetails(1, "Changed", "", "Changed", "", "")

        manager.importBackup { ByteArrayInputStream(full) }
        val restored = checkNotNull(pack.profile(1))
        assertEquals("com.example.custompack", restored.customPackageName)
        assertEquals(icon, restored.packIcon)
        assertEquals("pack.logo.source", ProfilePackIconCodec.sourcePackName(restored.packIcon))

        val imported = manager.importFile { ByteArrayInputStream(shared) }
        val added = checkNotNull(pack.profile(checkNotNull(imported.importedProfileId)))
        assertEquals("", added.customPackageName)
        assertEquals(icon, added.packIcon)
        assertTrue(added.hasUnbuiltChanges)
        assertTrue(pack.distinctSourcePacks().contains("pack.logo.source"))
    }

    private val draftInvalidation by lazy { EditorDraftInvalidation(EditorDraftStore(context), UnsavedIconJournalStore(context)) }
    private lateinit var context: Context
    private lateinit var packDb: RenkinPackDatabase
    private lateinit var watchDb: WatchDatabase
    private lateinit var pack: RenkinPackRepository
    private lateinit var watch: WatchRepository
    private lateinit var manager: BackupManager
    private lateinit var recovery: BackupRecoveryStore

    @Before fun setup(): Unit = runBlocking {
        StorageOperationGate.resetForTests()
        context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        packDb = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        watchDb = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
        pack = RenkinPackRepository(packDb)
        watch = WatchRepository(watchDb)
        manager = BackupManager(context, pack, watch, editorDraftInvalidation = draftInvalidation)
        recovery = BackupRecoveryStore(context)
        context.dataStore.edit { it.clear() }
    }

    @After fun cleanup() {
        BackupOsShadow.rejectRename = null
        StorageOperationGate.resetForTests()
        packDb.close()
        watchDb.close()
    }

    private suspend fun seed(name: String, payload: String = "source-$name"): DbApplication = StorageOperationGate.run {
        val state = checkNotNull(modifierInputForResult(ModifierInputTestIcon(payload), modifierInputTestOptions(), ModifierInputOrigin.UPLOAD))
        val prepared = checkNotNull(IconModifierInputStore(context).prepare(state))
        val row = DbApplication("app.one", "Main", false, false, "baked-$name", baseDrawable = "base-$name",
            modifierRecipe = checkNotNull(StoredIconModifierRecipe.fromRenderedOptions(state.options)), modifierInput = prepared.document)
        pack.replaceEverything(listOf(Profile(id = 1, name = name)), listOf(row))
        ProfileStyleStore(pack, context.dataStore).initialize(force = true)
        row
    }

    private suspend fun export(): ByteArray = ByteArrayOutputStream().also { manager.exportBackup { it } }.toByteArray()

    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private fun inputFile(row: DbApplication) = IconModifierInputStore.file(context,
        checkNotNull(StoredIconModifierInput.artwork(row.modifierInput)).hash)

    @Test fun fullBackupExternalizesAndDeduplicatesSourcesThenRestoresTheirBytes() = runBlocking {
        val incoming = seed("Incoming", "A".repeat(2_200_000))
        pack.replaceAll(1, listOf(incoming, incoming.copy(packageName = "app.two")))
        val bytes = export()
        val entries = entries(bytes)
        val inputs = entries.filterKeys { it.startsWith("icon-inputs/") }
        assertEquals(1, inputs.size)
        assertEquals(2_200_000, inputs.values.single().size)
        assertTrue(entries.getValue("data.json").size < 16_384)
        seed("Current")
        assertTrue(inputFile(incoming).delete())
        manager.importBackup { ByteArrayInputStream(bytes) }
        assertEquals(2, pack.getAll(1).size)
        assertEquals(incoming.modifierInput, pack.getAll(1).first().modifierInput)
        assertEquals("A".repeat(2_200_000), inputFile(incoming).readText())
        assertEquals(1, IconModifierInputStore.directory(context).listFiles()!!.size)
    }

    @Test fun sharedProfileExcludesHiddenSourceAndImportsOnlyReviewedArtwork() = runBlocking {
        val original = seed("Shared")
        val output = ByteArrayOutputStream()
        manager.exportProfile(1) { output }
        val entries = entries(output.toByteArray())
        assertFalse(entries.keys.any { it.startsWith("icon-inputs/") })
        val data = BackupCodec.decode(entries.getValue("data.json").toString(Charsets.UTF_8), BackupCodec.FORMAT_VERSION)
        assertEquals("", data.profiles.single().icons.single().modifierInput)
        assertEquals(original.drawable, data.profiles.single().icons.single().drawable)
        assertEquals(original.modifierRecipe, data.profiles.single().icons.single().modifierRecipe)
        manager.importFile { ByteArrayInputStream(output.toByteArray()) }
        val imported = pack.getAll(pack.profiles().single { it.id != 1L }.id).single()
        assertEquals(original.drawable, imported.drawable)
        assertEquals("", imported.modifierInput)
        assertEquals(original.modifierInput, pack.getAll(1).single().modifierInput)
        assertTrue(inputFile(original).exists())
    }

    @Test fun missingOrCorruptSourcesAreOmittedWithoutDroppingBakedIcons() = runBlocking {
        for (missing in listOf(false, true)) {
            val original = seed("Optional")
            if (missing) assertTrue(inputFile(original).delete()) else inputFile(original).writeText("corrupt")
            val entries = entries(export())
            assertFalse(entries.keys.any { it.startsWith("icon-inputs/") })
            val icon = BackupCodec.decode(entries.getValue("data.json").toString(Charsets.UTF_8), BackupCodec.FORMAT_VERSION).profiles.single().icons.single()
            assertEquals(original.drawable, icon.drawable)
            assertEquals(original.baseDrawable, icon.baseDrawable)
            assertEquals("", icon.modifierInput)
        }
    }

    @Test fun corruptedImportedSourceWithValidZipCrcDropsOnlyOptionalInput() = runBlocking {
        val original = seed("Incoming")
        val archiveEntries = entries(export()).mapValues { (name, bytes) ->
            if (name.startsWith("icon-inputs/")) "tampered-source".toByteArray() else bytes
        }
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                archiveEntries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        seed("Current")
        manager.importBackup { ByteArrayInputStream(archive) }
        val restored = pack.getAll(1).single()
        assertEquals(original.drawable, restored.drawable)
        assertEquals(original.baseDrawable, restored.baseDrawable)
        assertEquals(original.modifierRecipe, restored.modifierRecipe)
        assertEquals("", restored.modifierInput)
        assertTrue(IconModifierInputStore.directory(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun previousStateRestoresItsSourceAndRemovesImportedSources() = runBlocking {
        val incoming = seed("Incoming")
        val bytes = export()
        val current = seed("Current")
        manager.importBackup { ByteArrayInputStream(bytes) }
        assertEquals(incoming.modifierInput, pack.getAll(1).single().modifierInput)
        assertFalse(inputFile(current).exists())
        manager.restorePreviousState { it() }
        assertEquals(current.modifierInput, pack.getAll(1).single().modifierInput)
        assertEquals("source-Current", inputFile(current).readText())
        assertFalse(inputFile(incoming).exists())
        assertNull(recovery.pendingSnapshot())
    }

    @Test fun failedFileCommitRollsBackRowsAndSourceSetTogether() = runBlocking {
        val incoming = seed("Incoming")
        val bytes = export()
        val current = seed("Current")
        val target = inputFile(incoming).absolutePath
        var rejected = false
        BackupOsShadow.rejectRename = { source, destination ->
            if (!rejected && source.contains("backup-file-commit-") && destination == target) { rejected = true; true } else false
        }
        assertTrue(runCatching { manager.importBackup { ByteArrayInputStream(bytes) } }.isFailure)
        assertTrue(rejected)
        assertEquals(current, pack.getAll(1).single())
        assertEquals("source-Current", inputFile(current).readText())
        assertFalse(inputFile(incoming).exists())
        assertNull(recovery.pendingSnapshot())
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }

    @Test fun startupRecoveryRestoresReferencedFilesBeforeOrphanCollection() = runBlocking {
        val original = seed("Original")
        val snapshot = StorageOperationGate.run { recovery.capture(pack, watch) }
        recovery.activate(snapshot)
        val current = seed("Interrupted")
        assertTrue(inputFile(original).delete())
        StorageOperationGate.installRecovery { manager.recoverPendingRestore() }
        StorageOperationGate.run { }
        assertEquals(original, pack.getAll(1).single())
        assertEquals("source-Original", inputFile(original).readText())
        assertFalse(inputFile(current).exists())
        assertNull(recovery.pendingSnapshot())
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }
}
