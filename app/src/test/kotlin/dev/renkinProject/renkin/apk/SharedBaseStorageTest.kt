package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.Color
import androidx.room.Room
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.editor.EditorDraftFingerprints
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs

/** Issue #19: a base identical to the rendered icon is stored once, and reads back as before. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedBaseStorageTest {
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
        database.close()
        StorageOperationGate.resetForTests()
    }

    private fun pixels(color: Int, size: Int = 4) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun bitmapIcon(color: Int) = BitmapIconDrawable(pixels(color))

    private fun adaptiveIcon(color: Int) = AdaptiveIconPackDrawable(
        pixels(color, AdaptiveIconPackDrawable.LAYER_SIZE).getBytes(Bitmap.CompressFormat.PNG, 100),
        pixels(AndroidColor.BLACK, AdaptiveIconPackDrawable.LAYER_SIZE).getBytes(Bitmap.CompressFormat.PNG, 100)
    )

    private suspend fun saveAndRead(rendered: IconPackDrawable, base: IconPackDrawable) = run {
        store.save(1, listOf(PackageInfoStruct(
            appName = "App", packageName = "app.one", activityName = "Main", icon = ColorDrawable(0), iconID = 0,
            createdIcon = rendered, baseIcon = base
        )))
        repo.getAll(1).single()
    }

    @Test fun unchangedBaseIsStoredOnceAndRestoresAsADetachedCopy() = runBlocking {
        val icon = bitmapIcon(AndroidColor.BLUE)
        val row = saveAndRead(icon, icon)
        assertEquals("", row.baseDrawable)
        assertTrue(row.drawable.isNotEmpty())

        val restored = store.decodeRow(row, Color.Black)
        assertNotSame(restored.icon, restored.baseIcon)
        assertEquals(AndroidColor.BLUE, restored.baseIcon!!.toBitmap().getPixel(0, 0))
    }

    @Test fun separateBaseWithTheSamePayloadIsAlsoStoredOnce() = runBlocking {
        val row = saveAndRead(bitmapIcon(AndroidColor.BLUE), bitmapIcon(AndroidColor.BLUE))
        assertEquals("", row.baseDrawable)
    }

    @Test fun changedBaseKeepsItsOwnPayload() = runBlocking {
        val row = saveAndRead(bitmapIcon(AndroidColor.RED), bitmapIcon(AndroidColor.BLUE))
        assertTrue(row.baseDrawable.isNotEmpty())
        val restored = store.decodeRow(row, Color.Black)
        assertEquals(AndroidColor.RED, restored.icon!!.toBitmap().getPixel(0, 0))
        assertEquals(AndroidColor.BLUE, restored.baseIcon!!.toBitmap().getPixel(0, 0))
    }

    @Test fun adaptiveLayersAreStoredOnceAndRestoredAsTheirOwnInstance() = runBlocking {
        val icon = adaptiveIcon(AndroidColor.RED)
        val row = saveAndRead(icon, icon)
        assertEquals("", row.baseDrawable)

        val restored = store.decodeRow(row, Color.Black)
        val base = restored.baseIcon as AdaptiveIconPackDrawable
        assertNotSame(restored.icon, base)
        assertEquals(icon.toDbString(), base.toDbString())
    }

    @Test fun compactingARowKeepsItsDraftFingerprint() = runBlocking {
        val icon = bitmapIcon(AndroidColor.BLUE)
        val compacted = saveAndRead(icon, icon)
        val repeated = compacted.copy(baseDrawable = compacted.drawable)
        assertEquals(
            EditorDraftFingerprints.icon(1, "app.one", "Main", repeated),
            EditorDraftFingerprints.icon(1, "app.one", "Main", compacted)
        )
    }
}
