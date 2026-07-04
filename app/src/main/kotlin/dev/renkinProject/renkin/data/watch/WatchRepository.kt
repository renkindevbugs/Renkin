package dev.renkinProject.renkin.data.watch

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * High-level access to the icon-watch store. UI observes [rules] / [completedCount];
 * the checker (phase 3) uses the suggestion/state helpers. Mutations run inside a
 * Room transaction so a rule and its child rows stay consistent.
 */
class WatchRepository(private val db: WatchDatabase) {
    /** Production entry point: uses the shared singleton database. Tests use the primary
     * constructor with an in-memory [WatchDatabase] instead. */
    constructor(context: Context) : this(WatchDatabase.get(context))

    private val dao = db.watchDao()

    fun rules(profileId: Long): Flow<List<RuleWithDetails>> = dao.observeRules(profileId)
    fun completedCount(profileId: Long): Flow<Int> = dao.observeCompletedCount(profileId)

    suspend fun getRule(ruleId: Long): RuleWithDetails? = dao.getRuleWithDetails(ruleId)

    suspend fun getActiveRules(): List<RuleWithDetails> = dao.getActiveRules()

    /** Creates a new active rule owned by [profileId]. [packPackages] is ignored when [watchAllPacks]. */
    suspend fun createRule(
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>,
        profileId: Long
    ): Long = db.withTransaction {
        val ruleId = dao.insertRule(WatchRule(watchAllPacks = watchAllPacks, profileId = profileId))
        writeRuleChildren(ruleId, apps, watchAllPacks, packPackages)
        ruleId
    }

    /** Replaces the apps/packs of an existing rule (used when editing). */
    suspend fun updateRule(
        ruleId: Long,
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>
    ) = db.withTransaction {
        val rule = dao.getRule(ruleId) ?: return@withTransaction
        dao.updateRule(rule.copy(watchAllPacks = watchAllPacks))
        dao.deleteAppsForRule(ruleId)
        dao.deletePacksForRule(ruleId)
        writeRuleChildren(ruleId, apps, watchAllPacks, packPackages)
        dao.pruneOrphanStates()
    }

    private suspend fun writeRuleChildren(
        ruleId: Long,
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>
    ) {
        dao.insertApps(apps.map { WatchRuleApp(ruleId, it.packageName, it.activityName) })
        if (!watchAllPacks) {
            dao.insertPacks(packPackages.map { WatchRulePack(ruleId, it) })
        }
    }

    /** Deletes a rule and everything hanging off it (apps, packs, suggestions). */
    suspend fun deleteRule(ruleId: Long) = db.withTransaction {
        dao.deleteCandidatesForRule(ruleId)
        dao.deleteSuggestionsForRule(ruleId)
        dao.deleteAppsForRule(ruleId)
        dao.deletePacksForRule(ruleId)
        dao.deleteRule(ruleId)
        dao.pruneOrphanStates()
    }

    /**
     * Deletes every rule owned by [profileId] — called when the profile itself is deleted, so
     * the worker stops firing notifications for a profile that no longer exists.
     */
    suspend fun deleteRulesForProfile(profileId: Long) = deleteRules(dao.ruleIdsForProfile(profileId))

    /** Deletes several rules (and everything hanging off them) in one transaction. */
    suspend fun deleteRules(ruleIds: List<Long>) = db.withTransaction {
        ruleIds.forEach { ruleId ->
            dao.deleteCandidatesForRule(ruleId)
            dao.deleteSuggestionsForRule(ruleId)
            dao.deleteAppsForRule(ruleId)
            dao.deletePacksForRule(ruleId)
            dao.deleteRule(ruleId)
        }
        dao.pruneOrphanStates()
    }

    // --- Detection (phase 3) -------------------------------------------------

    suspend fun getState(packageName: String, activityName: String, iconPackPackage: String): WatchState? =
        dao.getState(packageName, activityName, iconPackPackage)

    suspend fun upsertState(state: WatchState) = dao.upsertState(state)

    /** Debug only: stale all baselines so the next check fires for any app with an icon. */
    suspend fun debugStaleAllStates() = dao.debugStaleAllStates()

    suspend fun getSuggestion(id: Long): IconSuggestion? = dao.getSuggestion(id)

    suspend fun getCandidates(suggestionId: Long): List<IconSuggestionCandidate> = dao.getCandidates(suggestionId)

    suspend fun deleteSuggestion(id: Long) = db.withTransaction {
        dao.deleteCandidates(id)
        dao.deleteSuggestion(id)
    }

    /**
     * Records that a new icon was found for [app] in [originalRuleId]. The app is split
     * into its own completed rule (recording the matched packs) — or, if it was the
     * rule's only app, the rule itself is completed. Returns the new suggestion's id.
     */
    suspend fun completeWithSuggestion(
        originalRuleId: Long,
        app: AppComponent,
        candidates: List<CandidateInput>
    ): Long = db.withTransaction {
        val original = dao.getRuleWithDetails(originalRuleId) ?: return@withTransaction -1L
        val matchedPacks = candidates.map { it.iconPackPackage }.distinct()
        val now = System.currentTimeMillis()

        val completedRuleId: Long
        if (original.apps.size <= 1) {
            dao.updateRule(original.rule.copy(completed = true, completedAt = now, watchAllPacks = false))
            dao.deletePacksForRule(originalRuleId)
            dao.insertPacks(matchedPacks.map { WatchRulePack(originalRuleId, it) })
            completedRuleId = originalRuleId
        } else {
            // The split-off completed rule stays in the same profile as the original.
            completedRuleId = dao.insertRule(WatchRule(watchAllPacks = false, completed = true, completedAt = now, profileId = original.rule.profileId))
            dao.insertApps(listOf(WatchRuleApp(completedRuleId, app.packageName, app.activityName)))
            dao.insertPacks(matchedPacks.map { WatchRulePack(completedRuleId, it) })
            dao.deleteApp(originalRuleId, app.packageName, app.activityName)
        }

        val suggestionId = dao.insertSuggestion(
            IconSuggestion(ruleId = completedRuleId, packageName = app.packageName, activityName = app.activityName)
        )
        dao.insertCandidates(candidates.map {
            IconSuggestionCandidate(suggestionId, it.iconPackPackage, it.drawableName, it.iconHash)
        })
        suggestionId
    }
}

/** A newly-found icon for an app from one pack, collected during a check. */
data class CandidateInput(
    val iconPackPackage: String,
    val drawableName: String,
    val iconHash: String
)
