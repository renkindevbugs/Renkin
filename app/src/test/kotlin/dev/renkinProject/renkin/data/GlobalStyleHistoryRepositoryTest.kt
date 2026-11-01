package dev.renkinProject.renkin.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalStyleHistoryRepositoryTest {
    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RenkinPackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RenkinPackRepository(db)
        // The app creates the default profile when it opens the database; in-memory tests must too.
        runBlocking { repo.ensureDefaultProfile() }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun styles(profileId: Long) = repo.globalStyleHistory(profileId).map { it.style }

    @Test
    fun theFirstApplyAlsoRecordsTheLookBeforeIt() = runBlocking {
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "circle", 10, previousStyle = "plain")

        assertEquals(listOf("circle", "plain"), styles(DEFAULT_PROFILE_ID))
    }

    @Test
    fun aFirstApplyThatChangesNothingRecordsOneEntry() = runBlocking {
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "plain", 10, previousStyle = "plain")

        assertEquals(listOf("plain"), styles(DEFAULT_PROFILE_ID))
    }

    @Test
    fun laterAppliesSkipARecipeEqualToTheNewestAndIgnoreThePreviousLook() = runBlocking {
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "circle", 10, previousStyle = "plain")
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "circle", 20, previousStyle = "circle")
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "outline", 30, previousStyle = "unrelated")
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "circle", 40, previousStyle = "outline")

        assertEquals(listOf("circle", "outline", "circle", "plain"), styles(DEFAULT_PROFILE_ID))
    }

    @Test
    fun onlyTheNewestEntriesOfEachProfileAreKept() = runBlocking {
        val other = repo.createProfile(Profile(name = "Other"))
        repo.recordGlobalStyle(other, "kept", 1, previousStyle = "kept")
        repeat(GLOBAL_STYLE_HISTORY_LIMIT + 5) { index ->
            repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "look $index", index.toLong(), previousStyle = "look $index")
        }

        val history = styles(DEFAULT_PROFILE_ID)
        assertEquals(GLOBAL_STYLE_HISTORY_LIMIT, history.size)
        assertEquals("look ${GLOBAL_STYLE_HISTORY_LIMIT + 4}", history.first())
        assertEquals("look 5", history.last())
        assertEquals(listOf("kept"), styles(other))
    }

    @Test
    fun deletingAnEntryNeedsItsOwnProfile() = runBlocking {
        val other = repo.createProfile(Profile(name = "Other"))
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "circle", 10, previousStyle = "circle")
        val entry = repo.globalStyleHistory(DEFAULT_PROFILE_ID).single()

        repo.deleteGlobalStyle(other, entry.id)
        assertEquals(listOf("circle"), styles(DEFAULT_PROFILE_ID))
        repo.deleteGlobalStyle(DEFAULT_PROFILE_ID, entry.id)
        assertTrue(styles(DEFAULT_PROFILE_ID).isEmpty())
    }

    @Test
    fun deletingAProfileDeletesItsHistory() = runBlocking {
        val other = repo.createProfile(Profile(name = "Other"))
        repo.recordGlobalStyle(other, "circle", 10, previousStyle = "circle")
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "outline", 10, previousStyle = "outline")

        repo.deleteProfile(other)

        assertTrue(styles(other).isEmpty())
        assertEquals(listOf("outline"), styles(DEFAULT_PROFILE_ID))
    }

    @Test
    fun aFullRestoreClearsEveryHistoryEvenForReusedProfileIds() = runBlocking {
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "device look", 10, previousStyle = "device look")

        repo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Restored")), emptyList())

        assertTrue(styles(DEFAULT_PROFILE_ID).isEmpty())
    }

    @Test
    fun aBakeIsAlwaysItsOwnEntryEvenWhenItRepeatsTheNewestRecipe() = runBlocking {
        repo.recordGlobalStyle(DEFAULT_PROFILE_ID, "plain", 10, previousStyle = "plain")

        val id = repo.recordGlobalStyleBake(DEFAULT_PROFILE_ID, "plain", 20, previousStyle = "scaled")

        assertEquals(listOf("plain", "plain"), styles(DEFAULT_PROFILE_ID))
        assertEquals(id, repo.globalStyleHistory(DEFAULT_PROFILE_ID).first().id)
        assertTrue(id in repo.globalStyleHistoryIds())
    }

    @Test
    fun aFirstBakeAlsoRecordsTheLookItBaked() = runBlocking {
        repo.recordGlobalStyleBake(DEFAULT_PROFILE_ID, "plain", 20, previousStyle = "scaled")

        assertEquals(listOf("plain", "scaled"), styles(DEFAULT_PROFILE_ID))
    }
}
