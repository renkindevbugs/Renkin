package dev.renkinProject.renkin.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import dev.renkinProject.renkin.data.transfer.BackupDatabaseSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/** Issue #19: stored icons larger than one CursorWindow row must still load, export and recover. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class OversizedIconRowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository

    // Distinct characters per position, so a misplaced or repeated chunk cannot go unnoticed.
    private fun artwork(length: Int, seed: Char) = buildString(length) {
        for (index in 0 until length) append(('A' + (index + seed.code) % 26))
    }

    private val big = DbApplication(
        "app.big", "Main", isAdaptiveIcon = true, isXml = true,
        drawable = artwork(OversizedText.INLINE_CHARS * 5 + 7, 'r'),
        calendarEnabled = true, calendarPrefix = "cal_", profileId = 1,
        baseDrawable = artwork(OversizedText.INLINE_CHARS + 1, 'b'), baseIsAdaptiveIcon = true, baseIsXml = true,
        modifierRecipe = "recipe"
    )
    private val baseOnly = big.copy(packageName = "app.base", drawable = "small", baseDrawable = artwork(OversizedText.CHUNK_CHARS * 2, 'x'))
    private val small = DbApplication("app.small", "Main", false, false, "tiny", profileId = 1, baseDrawable = "base")
    private val otherProfile = big.copy(profileId = 2, drawable = artwork(OversizedText.INLINE_CHARS * 2, 'o'))

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        repo = RenkinPackRepository(db)
        db.profileDao().insert(Profile(id = 2, name = "Second"))
        db.renkinPackDao().insertAll(listOf(big, baseOnly, small, otherProfile))
    }

    @After fun tearDown() = db.close()

    @Test fun rowQueriesLeaveLongArtworkOut() {
        val inline = db.renkinPackDao().getAllInline(1).associateBy { it.packageName }
        assertEquals("", inline.getValue("app.big").drawable)
        assertEquals("", inline.getValue("app.big").baseDrawable)
        assertEquals(small, inline.getValue("app.small"))
    }

    @Test fun profileReadReturnsEveryStoredValueExactly() = runBlocking {
        assertEquals(setOf(big, baseOnly, small), repo.getAll(1).toSet())
        assertEquals(listOf(otherProfile), repo.getAll(2))
    }

    @Test fun editorBaselineAndExportReadLongArtwork() = runBlocking {
        assertEquals(big, repo.editorBaseline(1, "app.big", "Main"))
        assertEquals(small, repo.editorBaseline(1, "app.small", "Main"))
        assertEquals(setOf(big, baseOnly, small, otherProfile), repo.getAllProfilesApplications().toSet())
    }

    @Test fun fingerprintsUseTheCompleteRenderedArtwork() = runBlocking {
        val row = repo.fingerprintRows(1).single { it.packageName == "app.big" }
        assertEquals(big.drawable, row.drawable)
        assertTrue(row.calendarEnabled)
    }

    @Test fun recoverySnapshotPreservesLongArtwork() = runBlocking {
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val file = context.filesDir.resolve("oversized-snapshot.json")
        try {
            val sql = db.openHelper.writableDatabase
            BackupDatabaseSnapshot.write(sql, file)
            sql.execSQL("DELETE FROM DbApplication")
            BackupDatabaseSnapshot.checkCompatibility(sql, file)
            db.withTransaction { BackupDatabaseSnapshot.restore(sql, file) }
            assertEquals(setOf(big, baseOnly, small, otherProfile), repo.getAllProfilesApplications().toSet())
        } finally { file.delete() }
    }

    @Test fun valueChangedBetweenChunksIsRejected() {
        val parts = listOf("abc", "")
        var call = 0
        assertThrows(IllegalStateException::class.java) {
            OversizedText.read(6) { _, _ -> parts[call++] }
        }
    }
}
