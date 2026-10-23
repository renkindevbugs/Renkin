package dev.renkinProject.renkin.data.watch

import android.app.Application
import androidx.room.Room
import dev.renkinProject.renkin.data.StorageOperationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The checker commits one rule's baselines and suggestion together. A baseline that survived
 * without its suggestion would make the next check skip the same pack version, so a failed or
 * cancelled commit must leave the old baseline and a retry must still find the change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchCheckedRuleCommitTest {
    private lateinit var db: WatchDatabase
    private lateinit var repo: WatchRepository
    private val app = AppComponent("com.a", "A")
    private val candidate = CandidateInput("pack1", "a", "new-hash")

    @Before fun setUp() {
        StorageOperationGate.resetForTests()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WatchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WatchRepository(db)
    }

    @After fun tearDown() {
        StorageOperationGate.resetForTests()
        db.close()
    }

    private suspend fun ruleWithOldBaseline(): Long = repo.saveRule(
        null, listOf(app), false, listOf("pack1"), 1L,
        listOf(BaselineInput("com.a", "A", "pack1", 1, "a", "old-hash", 0))
    )

    private fun advancedState(ruleId: Long) = WatchState(
        ruleId = ruleId, packageName = "com.a", activityName = "A", iconPackPackage = "pack1",
        lastPackVersionCode = 2, lastIconName = "a", lastIconHash = "new-hash", lastCheckedAt = 1
    )

    private suspend fun commitCheck(ruleId: Long): Long = repo.commitCheckedRule {
        repo.upsertStates(listOf(advancedState(ruleId)))
        repo.completeWithSuggestion(ruleId, app, listOf(candidate))
    }

    private suspend fun assertOldBaselineKept(ruleId: Long) {
        val state = repo.getStatesForRule(ruleId).single()
        assertEquals(1L, state.lastPackVersionCode)
        assertEquals("old-hash", state.lastIconHash)
        assertFalse(repo.getRule(ruleId)!!.rule.completed)
        assertEquals(0, repo.suggestionCount())
    }

    @Test fun failedSuggestionRollsTheBaselineBackAndARetryStillFindsTheChange() = runBlocking {
        val ruleId = ruleWithOldBaseline()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER fail_suggestion BEFORE INSERT ON icon_suggestion BEGIN SELECT RAISE(ABORT, 'disk full'); END"
        )

        assertTrue(runCatching { commitCheck(ruleId) }.isFailure)
        assertOldBaselineKept(ruleId)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_suggestion")
        assertTrue(commitCheck(ruleId) > 0)
        assertTrue(repo.getRule(ruleId)!!.rule.completed)
        assertEquals(1, repo.suggestionCount())
    }

    @Test fun cancellationBetweenTheWritesRollsTheBaselineBack() = runBlocking {
        val ruleId = ruleWithOldBaseline()
        val baselineWritten = CompletableDeferred<Unit>()

        val commit = async(Dispatchers.Default) {
            repo.commitCheckedRule {
                repo.upsertStates(listOf(advancedState(ruleId)))
                baselineWritten.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5000) { baselineWritten.await() }
        commit.cancel()
        runCatching { commit.await() }

        assertOldBaselineKept(ruleId)
    }
}
