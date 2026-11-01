package dev.renkinProject.renkin.data.watch

import android.content.Context
import androidx.room.withTransaction
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.transfer.BackupDatabaseSnapshot
import java.io.File
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

    private suspend fun <T> access(block: suspend () -> T): T = StorageOperationGate.run(block)

    private suspend fun <T> transaction(block: suspend () -> T): T = StorageOperationGate.run {
        db.withTransaction { block() }
    }

    /**
     * Commits one checked rule — its validation, baselines, component moves, splits and
     * suggestions — as a single transaction. A baseline that lands without its suggestion would
     * make the next check skip the same pack version and lose the suggestion.
     */
    internal suspend fun <T> commitCheckedRule(block: suspend () -> T): T = transaction(block)

    internal suspend fun writeRecoverySnapshot(file: File) = transaction {
        BackupDatabaseSnapshot.write(db.openHelper.writableDatabase, file)
    }

    internal suspend fun restoreRecoverySnapshot(file: File) = transaction {
        BackupDatabaseSnapshot.restore(db.openHelper.writableDatabase, file)
    }

    internal suspend fun checkRecoveryCompatibility(file: File) = access {
        BackupDatabaseSnapshot.checkCompatibility(db.openHelper.writableDatabase, file)
    }

    internal suspend fun flushRecoveryWrites() = access {
        BackupDatabaseSnapshot.flush(db.openHelper.writableDatabase)
    }

    private val dao = db.watchDao()

    fun rules(profileId: Long): Flow<List<RuleWithDetails>> = StorageOperationGate.observe(dao.observeRules(profileId))
    fun completedCount(profileId: Long): Flow<Int> = StorageOperationGate.observe(dao.observeCompletedCount(profileId))

    suspend fun getRule(ruleId: Long): RuleWithDetails? = access { dao.getRuleWithDetails(ruleId) }

    suspend fun getActiveRules(): List<RuleWithDetails> = access { dao.getActiveRules() }

    suspend fun getCompletedRules(): List<RuleWithDetails> = access { dao.getCompletedRules() }

    /**
     * Creates or updates an active rule and replaces its baseline in one transaction. Icon
     * resolution happens before this call, so the checker can never observe a half-saved rule.
     * [packPackages] is ignored when [watchAllPacks].
     */
    suspend fun saveRule(
        ruleId: Long?,
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>,
        profileId: Long,
        baseline: List<BaselineInput> = emptyList()
    ): Long = transaction {
        val savedRuleId = if (ruleId == null) {
            dao.insertRule(WatchRule(watchAllPacks = watchAllPacks, profileId = profileId))
        } else {
            val rule = dao.getRule(ruleId) ?: return@transaction -1L
            if (rule.completed) return@transaction -1L
            dao.updateRule(rule.copy(watchAllPacks = watchAllPacks))
            dao.deleteAppsForRule(ruleId)
            dao.deletePacksForRule(ruleId)
            dao.deleteStatesForRule(ruleId)
            ruleId
        }
        writeRuleChildren(savedRuleId, apps, watchAllPacks, packPackages)
        dao.upsertStates(baseline.map { it.toWatchState(savedRuleId) })
        dao.pruneOrphanStates()
        savedRuleId
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

    /** Deletes a rule and returns the notification-backed suggestion ids removed with it. */
    suspend fun deleteRule(ruleId: Long): List<Long> = deleteRules(listOf(ruleId))

    suspend fun ruleIdsForProfile(profileId: Long): List<Long> = access { dao.ruleIdsForProfile(profileId) }

    /** Rules whose profile is not among [profileIds]: leftovers of an interrupted profile deletion. */
    suspend fun ruleIdsOutsideProfiles(profileIds: Collection<Long>): List<Long> = access { dao.ruleIdsOutsideProfiles(profileIds) }

    suspend fun suggestionIdsForRules(ruleIds: List<Long>): List<Long> = access { dao.suggestionIdsForRules(ruleIds) }

    /** Deletes several rules atomically and returns their removed suggestion ids. */
    suspend fun deleteRules(ruleIds: List<Long>): List<Long> {
        if (ruleIds.isEmpty()) return emptyList()
        return transaction { deleteRuleRows(ruleIds) }
    }

    private suspend fun deleteRuleRows(ruleIds: List<Long>): List<Long> {
        val suggestionIds = dao.suggestionIdsForRules(ruleIds)
        ruleIds.forEach { ruleId ->
            dao.deleteCandidatesForRule(ruleId)
            dao.deleteSuggestionsForRule(ruleId)
            dao.deleteAppsForRule(ruleId)
            dao.deletePacksForRule(ruleId)
            dao.deleteRule(ruleId)
        }
        dao.pruneOrphanStates()
        return suggestionIds
    }

    internal suspend fun loadApplySnapshot(suggestionId: Long): WatchApplySnapshot? = transaction {
        readApplySnapshot(suggestionId)
    }

    private suspend fun readApplySnapshot(suggestionId: Long): WatchApplySnapshot? {
        val suggestion = dao.getSuggestion(suggestionId) ?: return null
        val details = dao.getRuleWithDetails(suggestion.ruleId) ?: return null
        val canonical = details.copy(
            apps = details.apps.sortedBy { it.packageName + "/" + it.activityName },
            packs = details.packs.sortedBy { it.iconPackPackage },
            suggestions = details.suggestions.sortedBy { it.id }
        )
        return WatchApplySnapshot(canonical, suggestion,
            dao.getCandidates(suggestionId).sortedBy { it.iconPackPackage }, StorageOperationGate.epoch,
            canonical.suggestions.flatMap { dao.getCandidates(it.id) }
                .sortedWith(compareBy<IconSuggestionCandidate> { it.suggestionId }.thenBy { it.iconPackPackage }))
    }

    internal suspend fun isCurrentApplySnapshot(snapshot: WatchApplySnapshot): Boolean = transaction {
        StorageOperationGate.epoch == snapshot.storageEpoch && readApplySnapshot(snapshot.suggestion.id) == snapshot
    }

    // A retry may run after deletion committed but notification cancellation or the return failed.
    internal suspend fun deleteAppliedRule(snapshot: WatchApplySnapshot): List<Long> = transaction {
        if (StorageOperationGate.epoch != snapshot.storageEpoch) throw WatchApplyTargetChangedException()
        val current = readApplySnapshot(snapshot.suggestion.id)
        if (current == null && dao.getRule(snapshot.rule.rule.id) == null) {
            return@transaction snapshot.rule.suggestions.map { it.id }
        }
        if (current != snapshot) throw WatchApplyTargetChangedException()
        deleteRuleRows(listOf(snapshot.rule.rule.id))
    }

    // --- Backup ---------------------------------------------------------------

    /** Every profile's rules with their children. */
    suspend fun getAllRules(): List<RuleWithDetails> = access { dao.getAllRulesWithDetails() }

    suspend fun getStatesForRule(ruleId: Long): List<WatchState> = access { dao.getStatesForRule(ruleId) }

    /**
     * Backup import: wipes the whole watch store and inserts [rules] under fresh ids.
     * Rule ids are regenerated, so every baseline, suggestion and candidate is re-owned by the
     * inserted rule. Legacy completed entries without candidates are skipped.
     */
    suspend fun replaceAllRules(rules: List<WatchRuleImport>) = transaction {
        dao.deleteAllCandidates()
        dao.deleteAllSuggestions()
        dao.deleteAllRuleApps()
        dao.deleteAllRulePacks()
        dao.deleteAllRules()
        dao.deleteAllStates()
        insertImportedRules(rules)
    }

    /** Adds active [rules] without touching existing ones — a shared-profile import is additive. */
    suspend fun insertRules(rules: List<WatchRuleImport>) = transaction {
        insertImportedRules(rules)
    }

    private suspend fun insertImportedRules(rules: List<WatchRuleImport>) {
        for (r in rules.filterNot { it.completed && it.suggestions.isEmpty() }) {
            val ruleId = dao.insertRule(
                WatchRule(
                    watchAllPacks = r.watchAllPacks,
                    completed = r.completed,
                    createdAt = r.createdAt,
                    completedAt = r.completedAt,
                    profileId = r.profileId
                )
            )
            writeRuleChildren(ruleId, r.apps, r.watchAllPacks, r.packs)
            dao.upsertStates(r.baselines.map { it.toWatchState(ruleId) })
            for (suggestion in r.suggestions) {
                val suggestionId = dao.insertSuggestion(
                    IconSuggestion(
                        ruleId = ruleId,
                        packageName = suggestion.packageName,
                        activityName = suggestion.activityName,
                        createdAt = suggestion.createdAt
                    )
                )
                dao.insertCandidates(suggestion.candidates.map {
                    IconSuggestionCandidate(
                        suggestionId = suggestionId,
                        iconPackPackage = it.iconPackPackage,
                        drawableName = it.drawableName,
                        iconHash = it.iconHash
                    )
                })
            }
        }
    }

    // --- Detection (phase 3) -------------------------------------------------

    suspend fun getState(ruleId: Long, packageName: String, activityName: String, iconPackPackage: String): WatchState? =
        access { dao.getState(ruleId, packageName, activityName, iconPackPackage) }

    suspend fun upsertState(state: WatchState) = access { dao.upsertState(state) }

    suspend fun upsertStates(states: List<WatchState>) = access { dao.upsertStates(states) }

    suspend fun upsertStates(ruleId: Long, states: List<BaselineInput>): Unit = access {
        dao.upsertStates(states.map { it.toWatchState(ruleId) })
    }

    /** Moves a watched app to its sole replacement launcher activity and installs its baseline. */
    suspend fun migrateRuleApp(
        ruleId: Long,
        oldApp: AppComponent,
        newApp: AppComponent,
        baseline: List<BaselineInput>
    ): Boolean = transaction {
        val rule = dao.getRuleWithDetails(ruleId) ?: return@transaction false
        if (rule.rule.completed || rule.apps.none {
                it.packageName == oldApp.packageName && it.activityName == oldApp.activityName
            }) return@transaction false

        val replacementAlreadyWatched = rule.apps.any {
            it.packageName == newApp.packageName && it.activityName == newApp.activityName
        }
        dao.deleteApp(ruleId, oldApp.packageName, oldApp.activityName)
        dao.deleteStatesForRuleApp(ruleId, oldApp.packageName, oldApp.activityName)
        if (!replacementAlreadyWatched) {
            dao.insertApps(listOf(WatchRuleApp(ruleId, newApp.packageName, newApp.activityName)))
            dao.upsertStates(baseline.map { it.toWatchState(ruleId) })
        }
        dao.pruneOrphanStates()
        true
    }

    /** Debug only: stale all baselines so the next check fires for any app with an icon. */
    suspend fun debugStaleAllStates() = access { dao.debugStaleAllStates() }

    suspend fun getSuggestion(id: Long): IconSuggestion? = access { dao.getSuggestion(id) }

    suspend fun getCandidates(suggestionId: Long): List<IconSuggestionCandidate> = access { dao.getCandidates(suggestionId) }

    suspend fun replaceCandidates(
        suggestionId: Long,
        candidates: List<CandidateInput>
    ): Boolean = transaction {
        if (dao.getSuggestion(suggestionId) == null || candidates.isEmpty()) {
            return@transaction false
        }
        dao.deleteCandidates(suggestionId)
        dao.insertCandidates(candidates.map {
            IconSuggestionCandidate(suggestionId, it.iconPackPackage, it.drawableName, it.iconHash)
        })
        true
    }

    suspend fun suggestionCount(): Int = access { dao.suggestionCount() }

    suspend fun deleteSuggestion(id: Long) = transaction {
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
    ): Long = transaction {
        val original = dao.getRuleWithDetails(originalRuleId) ?: return@transaction -1L
        if (original.rule.completed || original.apps.none {
                it.packageName == app.packageName && it.activityName == app.activityName
            }) return@transaction -1L
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
        dao.pruneOrphanStates()
        suggestionId
    }
}

/** A newly-found icon for an app from one pack, collected during a check. */
data class CandidateInput(
    val iconPackPackage: String,
    val drawableName: String,
    val iconHash: String
)

/** A resolved baseline before its new or existing owning rule id is known. */
data class BaselineInput(
    val packageName: String,
    val activityName: String,
    val iconPackPackage: String,
    val lastPackVersionCode: Long,
    val lastIconName: String?,
    val lastIconHash: String?,
    val lastCheckedAt: Long
)

private fun BaselineInput.toWatchState(ruleId: Long) = WatchState(
    ruleId = ruleId,
    packageName = packageName,
    activityName = activityName,
    iconPackPackage = iconPackPackage,
    lastPackVersionCode = lastPackVersionCode,
    lastIconName = lastIconName,
    lastIconHash = lastIconHash,
    lastCheckedAt = lastCheckedAt
)

/** One rule (plus children) to insert during a backup import — rule ids are regenerated. */
data class WatchRuleImport(
    val profileId: Long,
    val watchAllPacks: Boolean,
    val completed: Boolean,
    val createdAt: Long,
    val completedAt: Long?,
    val apps: List<AppComponent>,
    val packs: List<String>,
    val baselines: List<BaselineInput> = emptyList(),
    val suggestions: List<SuggestionImport> = emptyList()
)

data class SuggestionImport(
    val packageName: String,
    val activityName: String,
    val createdAt: Long,
    val candidates: List<CandidateInput>
)
