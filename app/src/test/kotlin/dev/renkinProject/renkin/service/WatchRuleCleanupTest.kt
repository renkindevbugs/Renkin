package dev.renkinProject.renkin.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.CandidateInput
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A deleted profile's watch rules go together with their posted notifications, and rules left
 * behind by an interrupted profile deletion are removed before the next check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchRuleCleanupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var watchDb: WatchDatabase
    private lateinit var packDb: RenkinPackDatabase
    private lateinit var watchRepo: WatchRepository
    private lateinit var packRepo: RenkinPackRepository

    @Before fun setUp() {
        StorageOperationGate.resetForTests()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        watchDb = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
        packDb = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        watchRepo = WatchRepository(watchDb)
        packRepo = RenkinPackRepository(packDb)
    }

    @After fun tearDown() {
        StorageOperationGate.resetForTests()
        watchDb.close()
        packDb.close()
    }

    /** A completed rule with one suggestion whose notification is posted; returns (ruleId, suggestionId). */
    private suspend fun notifiedSuggestion(profileId: Long, packageName: String): Pair<Long, Long> {
        val app = AppComponent(packageName, "Main")
        val ruleId = watchRepo.saveRule(null, listOf(app), false, listOf("pack"), profileId)
        val suggestionId = watchRepo.completeWithSuggestion(ruleId, app, listOf(CandidateInput("pack", "icon", "hash")))
        RenkinNotifications().postIconAvailable(context, suggestionId, packageName, listOf("pack"), "Profile")
        return ruleId to suggestionId
    }

    private fun postedIds(): Set<Int> = manager.activeNotifications.map { it.id }.toSet()

    @Test fun deletingRulesCancelsOnlyTheirNotificationsAndKeepsTheSummaryForOthers() = runBlocking {
        val (deletedRule, deletedSuggestion) = notifiedSuggestion(2L, "app.deleted")
        val (_, keptSuggestion) = notifiedSuggestion(1L, "app.kept")
        val before = postedIds()

        watchRepo.deleteRulesAndNotifications(context, listOf(deletedRule))

        val after = postedIds()
        assertEquals(before.size - 1, after.size)
        assertNull(watchRepo.getSuggestion(deletedSuggestion))
        assertTrue(watchRepo.getSuggestion(keptSuggestion) != null)
        assertNull(watchRepo.getRule(deletedRule))
    }

    @Test fun deletingTheLastSuggestionAlsoCancelsTheSummary() = runBlocking {
        val (ruleId, _) = notifiedSuggestion(2L, "app.only")
        assertEquals(2, postedIds().size)

        watchRepo.deleteRulesAndNotifications(context, listOf(ruleId))

        assertTrue(postedIds().isEmpty())
        assertEquals(0, watchRepo.suggestionCount())
    }

    @Test fun rulesOfADeletedProfileAreRemovedWhileOtherProfilesKeepTheirs() = runBlocking {
        packRepo.ensureDefaultProfile()
        val removedProfile = packRepo.createProfile(Profile(name = "Removed"))
        val (orphanRule, orphanSuggestion) = notifiedSuggestion(removedProfile, "app.orphan")
        val (keptRule, _) = notifiedSuggestion(1L, "app.kept")
        // The profile row is gone but the deletion was interrupted before its watch rules.
        packRepo.deleteProfile(removedProfile)

        removeWatchRulesOfDeletedProfiles(context, watchRepo, packRepo)

        assertNull(watchRepo.getRule(orphanRule))
        assertNull(watchRepo.getSuggestion(orphanSuggestion))
        assertTrue(watchRepo.getRule(keptRule) != null)
        assertEquals(2, postedIds().size)
    }

    @Test fun unreadableProfileListRemovesNothing() = runBlocking {
        val (ruleId, _) = notifiedSuggestion(1L, "app.kept")

        removeWatchRulesOfDeletedProfiles(context, watchRepo, packRepo)

        assertTrue(watchRepo.getRule(ruleId) != null)
        assertEquals(2, postedIds().size)
    }
}
