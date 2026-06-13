package dev.alembiconsProject.alembicons.data.watch

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * High-level access to the icon-watch store. UI observes [rules] / [completedCount];
 * the checker (phase 3) uses the suggestion/state helpers. Mutations run inside a
 * Room transaction so a rule and its child rows stay consistent.
 */
class WatchRepository(context: Context) {
    private val db = WatchDatabase.get(context)
    private val dao = db.watchDao()

    val rules: Flow<List<RuleWithDetails>> = dao.observeRules()
    val completedCount: Flow<Int> = dao.observeCompletedCount()

    suspend fun getRule(ruleId: Long): RuleWithDetails? = dao.getRuleWithDetails(ruleId)

    suspend fun getActiveRules(): List<RuleWithDetails> = dao.getActiveRules()

    /** Creates a new active rule. [packPackages] is ignored when [watchAllPacks]. */
    suspend fun createRule(
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>
    ): Long = db.withTransaction {
        val ruleId = dao.insertRule(WatchRule(watchAllPacks = watchAllPacks))
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
    }
}
