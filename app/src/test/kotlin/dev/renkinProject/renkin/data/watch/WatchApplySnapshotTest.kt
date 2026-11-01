package dev.renkinProject.renkin.data.watch

import android.app.Application
import androidx.room.Room
import dev.renkinProject.renkin.data.StorageOperationGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchApplySnapshotTest {
    private lateinit var db: WatchDatabase
    private lateinit var repo: WatchRepository
    @Before fun setup() {
        StorageOperationGate.resetForTests()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WatchDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = WatchRepository(db)
    }
    @After fun cleanup() { db.close(); StorageOperationGate.resetForTests() }
    private suspend fun snapshot(profile: Long = 1): WatchApplySnapshot {
        val app = AppComponent("test.app", "Main")
        val rule = repo.saveRule(null, listOf(app), false, listOf("test.pack"), profile)
        val id = repo.completeWithSuggestion(rule, app, listOf(CandidateInput("test.pack", "drawable", "hash")))
        return requireNotNull(repo.loadApplySnapshot(id))
    }
    private suspend fun expectChanged(block: suspend () -> Unit) {
        try { block(); fail("Changed target must not be deleted") } catch (_: WatchApplyTargetChangedException) { }
    }

    @Test fun cleanupDeletesOnlyCapturedRuleAndCanBeRepeated() = runBlocking {
        val first = snapshot(1)
        val other = snapshot(2)
        assertTrue(repo.isCurrentApplySnapshot(first))
        assertEquals(listOf(first.suggestion.id), repo.deleteAppliedRule(first))
        assertEquals(listOf(first.suggestion.id), repo.deleteAppliedRule(first))
        assertNotNull(repo.getRule(other.rule.rule.id))
        assertEquals(1, repo.suggestionCount())
    }

    @Test fun changedCandidateIsKept() = runBlocking {
        val captured = snapshot()
        repo.replaceCandidates(captured.suggestion.id, listOf(CandidateInput("test.pack", "new", "new-hash")))
        assertFalse(repo.isCurrentApplySnapshot(captured))
        expectChanged { repo.deleteAppliedRule(captured) }
        assertNotNull(repo.getSuggestion(captured.suggestion.id))
    }

    @Test fun storageReplacementRejectsEvenIdenticalIdsAndContent() = runBlocking {
        val captured = snapshot()
        StorageOperationGate.run { StorageOperationGate.markStorageReplaced() }
        assertFalse(repo.isCurrentApplySnapshot(captured))
        expectChanged { repo.deleteAppliedRule(captured) }
        assertNotNull(repo.getSuggestion(captured.suggestion.id))
    }

    @Test fun reusedRuleIdCannotDeleteAnotherProfile() = runBlocking {
        val captured = snapshot()
        repo.deleteRule(captured.rule.rule.id)
        db.watchDao().insertRule(captured.rule.rule.copy(profileId = 8))
        expectChanged { repo.deleteAppliedRule(captured) }
        assertEquals(8L, repo.getRule(captured.rule.rule.id)?.rule?.profileId)
    }

    @Test fun missingSuggestionDoesNotAuthorizeDeletingAnExistingRule() = runBlocking {
        val captured = snapshot()
        repo.deleteSuggestion(captured.suggestion.id)
        expectChanged { repo.deleteAppliedRule(captured) }
        assertNotNull(repo.getRule(captured.rule.rule.id))
    }

    @Test fun anotherSuggestionChangingInTheCapturedRulePreventsDeletion() = runBlocking {
        val original = snapshot()
        val otherId = db.watchDao().insertSuggestion(IconSuggestion(ruleId = original.rule.rule.id,
            packageName = "test.other", activityName = "Other"))
        db.watchDao().insertCandidates(listOf(IconSuggestionCandidate(otherId, "test.pack", "other", "other-hash")))
        val captured = requireNotNull(repo.loadApplySnapshot(original.suggestion.id))
        repo.replaceCandidates(otherId, listOf(CandidateInput("test.pack", "changed", "changed-hash")))
        expectChanged { repo.deleteAppliedRule(captured) }
        assertEquals(2, repo.suggestionCount())
    }
}
