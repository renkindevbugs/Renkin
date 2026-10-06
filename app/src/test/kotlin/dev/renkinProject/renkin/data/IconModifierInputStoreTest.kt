package dev.renkinProject.renkin.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.Color
import androidx.room.Room
import dev.renkinProject.renkin.apk.RenkinPackStore
import dev.renkinProject.renkin.data.transfer.BackupOsShadow
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.PackageInfoStruct
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class IconModifierInputStoreTest {
    @Test fun freshAdaptiveBitmapRetainsItsPipelineAndPreviewScaleAcrossSaveAndLoad() = runBlocking {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val input = BitmapIconDrawable(context.resources, bitmap, exportAsAdaptiveIcon = true, previewScale = 1.5f)
        val state = checkNotNull(modifierInputForResult(input, modifierInputTestOptions(),
            ModifierInputOrigin.FRESH_SOURCE, ModifierInputPipeline.IMAGE_EDIT))
        store.save(1, listOf(app(state)))
        val restored = checkNotNull(store.load(1, Color.Black).values.single().modifierInput)
        assertEquals(ModifierInputPipeline.IMAGE_EDIT, restored.pipeline)
        assertEquals(ModifierInputOrigin.FRESH_SOURCE, restored.origin)
        assertEquals(1.5f, restored.storedInput!!.previewScale)
        assertTrue(restored.storedInput!!.isAdaptive)
        assertFalse(restored.storedInput!!.isXml)
        assertNull(restored.liveInput)
    }

    private lateinit var context: Context
    private lateinit var database: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository
    private lateinit var store: RenkinPackStore

    @Before fun setup() = runBlocking {
        StorageOperationGate.resetForTests()
        context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        repo = RenkinPackRepository(database)
        repo.replaceEverything(listOf(Profile(id = 1, name = "Saved")), emptyList())
        store = RenkinPackStore(context, repo)
    }

    @After fun cleanup() {
        BackupOsShadow.rejectRename = null
        database.close()
        StorageOperationGate.resetForTests()
    }

    private fun app(state: IconModifierInput) = PackageInfoStruct(
        appName = "App", packageName = "app.one", activityName = "Main", icon = ColorDrawable(0), iconID = 0
    ).changeExport(BitmapIconDrawable(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.GREEN)
    }), modifierInput = state)

    private fun state(input: ModifierInputTestIcon = ModifierInputTestIcon()) =
        checkNotNull(modifierInputForResult(input, modifierInputTestOptions(), ModifierInputOrigin.UPLOAD))

    @Test fun largeSourceStaysOutsideRowsAndRepeatedSavesReuseItsFile() = runBlocking {
        val source = ModifierInputTestIcon("A".repeat(2_200_000))
        val state = state(source)
        val app = app(state)
        store.save(1, listOf(app))
        val row = repo.getAll(1).single()
        val artwork = checkNotNull(StoredIconModifierInput.artwork(row.modifierInput))
        val file = IconModifierInputStore.file(context, artwork.hash)
        assertEquals(2_200_000L, file.length())
        assertTrue(row.modifierInput.length < 4096)
        assertTrue(row.drawable.length + row.baseDrawable.length + row.modifierInput.length < 8192)
        assertTrue(IconModifierInputStore.validFile(file, artwork.hash))
        assertNull(state.liveInput)
        assertEquals(artwork, state.storedInput)
        val loaded = checkNotNull(store.load(1, Color.Black).values.single().modifierInput)
        assertNull(loaded.liveInput)
        assertEquals(artwork, loaded.storedInput)
        val timestamp = file.lastModified()
        store.save(1, listOf(app))
        assertEquals(1, source.encodeCalls)
        assertEquals(timestamp, file.lastModified())
        assertEquals(1, IconModifierInputStore.directory(context).listFiles()!!.size)
    }

    @Test fun failedDatabaseSaveDoesNotReleaseSourceAndStartupCollectsItsOrphan() = runBlocking {
        val source = ModifierInputTestIcon()
        val state = state(source)
        database.close()
        assertTrue(runCatching { store.save(1, listOf(app(state))) }.isFailure)
        assertSame(source, state.liveInput)
        assertNull(state.storedInput)
        assertEquals(1, IconModifierInputStore.directory(context).listFiles()!!.size)
        IconModifierInputStore.cleanupAfterProcessStart(context, emptyList())
        assertTrue(IconModifierInputStore.directory(context).listFiles()!!.isEmpty())
    }

    @Test fun failedRenameDropsOnlyOptionalMetadataAndCleansTemporaryFiles() = runBlocking {
        val source = ModifierInputTestIcon()
        val state = state(source)
        BackupOsShadow.rejectRename = { _, _ -> true }
        store.save(1, listOf(app(state)))
        assertEquals("", repo.getAll(1).single().modifierInput)
        assertSame(source, state.liveInput)
        assertNull(state.storedInput)
        assertNotNull(store.load(1, Color.Black).values.single().icon)
        assertTrue(IconModifierInputStore.directory(context).listFiles()!!.isEmpty())
    }

    @Test fun missingInputNeverDropsBakedArtworkAndSavingClearsItsReference() = runBlocking {
        val state = state()
        val app = app(state)
        store.save(1, listOf(app))
        val row = repo.getAll(1).single()
        val artwork = checkNotNull(state.storedInput)
        assertTrue(IconModifierInputStore.file(context, artwork.hash).delete())
        val loaded = store.decodeRow(row, Color.Black)
        assertNull(loaded.modifierInput)
        assertNotNull(loaded.icon)
        assertNotNull(loaded.baseIcon)
        store.save(1, listOf(app))
        assertEquals("", repo.getAll(1).single().modifierInput)
    }

    @Test fun contentHashDetectsCorruptionAndCleanupKeepsReferencedFilesOnly() = runBlocking {
        store.save(1, listOf(app(state())))
        val row = repo.getAll(1).single()
        val artwork = checkNotNull(StoredIconModifierInput.artwork(row.modifierInput))
        val root = IconModifierInputStore.directory(context)
        val file = IconModifierInputStore.file(context, artwork.hash)
        file.writeText("corrupt")
        assertFalse(IconModifierInputStore.validFile(file, artwork.hash))
        val orphan = root.resolve("${"f".repeat(64)}.input").apply { writeText("orphan") }
        val temporary = root.resolve(".interrupted.tmp").apply { writeText("temporary") }
        IconModifierInputStore.cleanupAfterProcessStart(context, listOf(row.modifierInput))
        assertTrue(file.exists())
        assertFalse(orphan.exists())
        assertFalse(temporary.exists())
        assertFalse(IconModifierInputStore.validName("../${artwork.hash}.input"))
        assertTrue(runCatching { IconModifierInputStore.file(context, "../unsafe") }.isFailure)
    }
}
