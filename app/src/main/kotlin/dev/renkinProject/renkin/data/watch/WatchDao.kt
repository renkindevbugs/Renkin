package dev.renkinProject.renkin.data.watch

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {

    // --- Rules ---------------------------------------------------------------

    @Insert
    suspend fun insertRule(rule: WatchRule): Long

    @Update
    suspend fun updateRule(rule: WatchRule)

    @Query("DELETE FROM watch_rule WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("SELECT * FROM watch_rule WHERE id = :ruleId")
    suspend fun getRule(ruleId: Long): WatchRule?

    @Query("SELECT id FROM watch_rule WHERE profileId = :profileId")
    suspend fun ruleIdsForProfile(profileId: Long): List<Long>

    @Transaction
    @Query("SELECT * FROM watch_rule WHERE profileId = :profileId ORDER BY completed ASC, createdAt DESC")
    fun observeRules(profileId: Long): Flow<List<RuleWithDetails>>

    @Transaction
    @Query("SELECT * FROM watch_rule WHERE completed = 0")
    suspend fun getActiveRules(): List<RuleWithDetails>

    @Transaction
    @Query("SELECT * FROM watch_rule WHERE id = :ruleId")
    suspend fun getRuleWithDetails(ruleId: Long): RuleWithDetails?

    /** Drives the home-screen bell badge (per profile). */
    @Query("SELECT COUNT(*) FROM watch_rule WHERE completed = 1 AND profileId = :profileId")
    fun observeCompletedCount(profileId: Long): Flow<Int>

    // --- Rule apps / packs ---------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<WatchRuleApp>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacks(packs: List<WatchRulePack>)

    @Query("DELETE FROM watch_rule_app WHERE ruleId = :ruleId")
    suspend fun deleteAppsForRule(ruleId: Long)

    @Query("DELETE FROM watch_rule_pack WHERE ruleId = :ruleId")
    suspend fun deletePacksForRule(ruleId: Long)

    @Query("DELETE FROM watch_rule_app WHERE ruleId = :ruleId AND packageName = :packageName AND activityName = :activityName")
    suspend fun deleteApp(ruleId: Long, packageName: String, activityName: String)

    @Query("SELECT COUNT(*) FROM watch_rule_app WHERE ruleId = :ruleId")
    suspend fun appCountForRule(ruleId: Long): Int

    // --- Suggestions ---------------------------------------------------------

    @Insert
    suspend fun insertSuggestion(suggestion: IconSuggestion): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(candidates: List<IconSuggestionCandidate>)

    @Query("SELECT * FROM icon_suggestion WHERE id = :id")
    suspend fun getSuggestion(id: Long): IconSuggestion?

    @Query("SELECT * FROM icon_suggestion_candidate WHERE suggestionId = :suggestionId")
    suspend fun getCandidates(suggestionId: Long): List<IconSuggestionCandidate>

    @Query("DELETE FROM icon_suggestion_candidate WHERE suggestionId = :suggestionId")
    suspend fun deleteCandidates(suggestionId: Long)

    @Query("DELETE FROM icon_suggestion WHERE id = :id")
    suspend fun deleteSuggestion(id: Long)

    @Query("DELETE FROM icon_suggestion_candidate WHERE suggestionId IN (SELECT id FROM icon_suggestion WHERE ruleId = :ruleId)")
    suspend fun deleteCandidatesForRule(ruleId: Long)

    @Query("DELETE FROM icon_suggestion WHERE ruleId = :ruleId")
    suspend fun deleteSuggestionsForRule(ruleId: Long)

    @Query("SELECT id FROM icon_suggestion WHERE ruleId IN (:ruleIds)")
    suspend fun suggestionIdsForRules(ruleIds: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM icon_suggestion")
    suspend fun suggestionCount(): Int

    // --- Watch state ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: WatchState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStates(states: List<WatchState>)

    @Query("DELETE FROM watch_state WHERE ruleId = :ruleId")
    suspend fun deleteStatesForRule(ruleId: Long)

    @Query("SELECT * FROM watch_state WHERE ruleId = :ruleId AND packageName = :packageName AND activityName = :activityName AND iconPackPackage = :iconPackPackage")
    suspend fun getState(ruleId: Long, packageName: String, activityName: String, iconPackPackage: String): WatchState?

    /** Drops baselines no longer referenced by their owning active rule. */
    @Query(
        "DELETE FROM watch_state WHERE NOT EXISTS (" +
            "SELECT 1 FROM watch_rule_app ra JOIN watch_rule r ON r.id = ra.ruleId " +
            "WHERE ra.ruleId = watch_state.ruleId AND r.completed = 0 " +
            "AND ra.packageName = watch_state.packageName AND ra.activityName = watch_state.activityName " +
            "AND (r.watchAllPacks = 1 OR EXISTS (SELECT 1 FROM watch_rule_pack rp " +
            "WHERE rp.ruleId = r.id AND rp.iconPackPackage = watch_state.iconPackPackage)))"
    )
    suspend fun pruneOrphanStates()

    /** Debug only: makes every baseline look outdated so the next check reports "new". */
    @Query("UPDATE watch_state SET lastPackVersionCode = -1, lastIconHash = 'debug-force'")
    suspend fun debugStaleAllStates()

    // --- Backup --------------------------------------------------------------

    /** Every profile's rules (active and completed) — backup export. */
    @Transaction
    @Query("SELECT * FROM watch_rule")
    suspend fun getAllRulesWithDetails(): List<RuleWithDetails>

    @Query("DELETE FROM watch_rule")
    suspend fun deleteAllRules()

    @Query("DELETE FROM watch_rule_app")
    suspend fun deleteAllRuleApps()

    @Query("DELETE FROM watch_rule_pack")
    suspend fun deleteAllRulePacks()

    @Query("DELETE FROM icon_suggestion")
    suspend fun deleteAllSuggestions()

    @Query("DELETE FROM icon_suggestion_candidate")
    suspend fun deleteAllCandidates()

    @Query("DELETE FROM watch_state")
    suspend fun deleteAllStates()
}
