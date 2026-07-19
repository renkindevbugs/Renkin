package dev.renkinProject.renkin.data.watch

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Room model for the icon-watch feature (see docs/icon-watch-feature.md).
 *
 * A [WatchRule] groups apps ([WatchRuleApp]) and the packs to monitor for them
 * ([WatchRulePack], or every installed pack when [WatchRule.watchAllPacks]). When a
 * watched pack publishes a new icon for a watched app, the app is split into its own
 * rule marked [WatchRule.completed] and an [IconSuggestion] (+ one
 * [IconSuggestionCandidate] per matching pack) is recorded for the apply modal.
 */
@Entity(tableName = "watch_rule")
data class WatchRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchAllPacks: Boolean = false,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    // Which profile owns this rule: the UI lists only the active profile's rules, while the
    // checker runs every profile's.
    @androidx.room.ColumnInfo(defaultValue = "1") val profileId: Long = 1
)

@Entity(tableName = "watch_rule_app", primaryKeys = ["ruleId", "packageName", "activityName"])
data class WatchRuleApp(
    val ruleId: Long,
    val packageName: String,
    val activityName: String
)

@Entity(tableName = "watch_rule_pack", primaryKeys = ["ruleId", "iconPackPackage"])
data class WatchRulePack(
    val ruleId: Long,
    val iconPackPackage: String
)

/** Baseline fingerprint per (rule, app, pack) so overlapping rules advance independently. */
@Entity(tableName = "watch_state", primaryKeys = ["ruleId", "packageName", "activityName", "iconPackPackage"])
data class WatchState(
    val ruleId: Long,
    val packageName: String,
    val activityName: String,
    val iconPackPackage: String,
    val lastPackVersionCode: Long,
    val lastIconName: String?,
    val lastIconHash: String?,
    val lastCheckedAt: Long
)

@Entity(tableName = "icon_suggestion")
data class IconSuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val packageName: String,
    val activityName: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "icon_suggestion_candidate", primaryKeys = ["suggestionId", "iconPackPackage"])
data class IconSuggestionCandidate(
    val suggestionId: Long,
    val iconPackPackage: String,
    val drawableName: String,
    val iconHash: String
)

/** A rule plus its apps, packs and any pending suggestions — for the watch screen. */
data class RuleWithDetails(
    @Embedded val rule: WatchRule,
    @Relation(parentColumn = "id", entityColumn = "ruleId") val apps: List<WatchRuleApp>,
    @Relation(parentColumn = "id", entityColumn = "ruleId") val packs: List<WatchRulePack>,
    @Relation(parentColumn = "id", entityColumn = "ruleId") val suggestions: List<IconSuggestion>
)

/** Lightweight (packageName, activityName) pair used when building/editing rules. */
data class AppComponent(val packageName: String, val activityName: String)
