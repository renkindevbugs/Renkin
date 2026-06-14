package dev.alembiconsProject.alembicons.service

import android.content.Context
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.RawItem
import dev.alembiconsProject.alembicons.data.toComponentInfo
import dev.alembiconsProject.alembicons.data.watch.AppComponent
import dev.alembiconsProject.alembicons.data.watch.CandidateInput
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.data.watch.WatchState
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.extension.contentHash
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans active watch rules and records a suggestion when a watched pack has published a
 * new icon for a watched app (see docs/icon-watch-feature.md, phase 3).
 *
 * Work is gated on each pack's versionCode: if the pack hasn't been updated since the
 * last check for an (app, pack) pair, it's skipped entirely — so a periodic run does
 * almost nothing unless a pack actually changed. The first time an (app, pack) is seen
 * it only records a baseline (no notification); a later change in the icon's content
 * hash is what fires a suggestion.
 *
 * Pure of any UI/notification side effects — it returns the suggestions it created so a
 * trigger (phase 4) / notifier (phase 5) can act on them.
 */
class WatchChecker(context: Context) {
    private val appMan = ApplicationManager(context)
    private val repo = WatchRepository(context)

    data class FiredSuggestion(
        val suggestionId: Long,
        val packageName: String,
        val activityName: String,
        val packPackages: List<String>
    )

    suspend fun runCheck(): List<FiredSuggestion> = withContext(Dispatchers.Default) {
        val fired = mutableListOf<FiredSuggestion>()
        val installedPacks = appMan.getIconPacks().associateBy { it.packageName }

        for (rule in repo.getActiveRules()) {
            val packPackages = if (rule.rule.watchAllPacks) {
                installedPacks.keys.toList()
            } else {
                rule.packs.map { it.iconPackPackage }
            }

            for (ruleApp in rule.apps) {
                val installedApp = InstalledApplication(ruleApp.packageName, ruleApp.activityName, 0)
                val candidates = mutableListOf<CandidateInput>()

                for (packPackage in packPackages) {
                    val pack = installedPacks[packPackage] ?: continue
                    val previous = repo.getState(ruleApp.packageName, ruleApp.activityName, packPackage)

                    // No update since last look → nothing to do (keeps periodic runs cheap)
                    if (previous != null && previous.lastPackVersionCode == pack.versionCode) continue

                    val (drawableName, hash) = resolveIcon(packPackage, installedApp)

                    // First sighting is only a baseline; a changed hash afterwards is "new"
                    val isNew = previous != null && hash != null && hash != previous.lastIconHash

                    repo.upsertState(
                        WatchState(
                            packageName = ruleApp.packageName,
                            activityName = ruleApp.activityName,
                            iconPackPackage = packPackage,
                            lastPackVersionCode = pack.versionCode,
                            lastIconName = drawableName,
                            lastIconHash = hash,
                            lastCheckedAt = System.currentTimeMillis()
                        )
                    )

                    if (isNew && drawableName != null && hash != null) {
                        candidates.add(CandidateInput(packPackage, drawableName, hash))
                    }
                }

                if (candidates.isNotEmpty()) {
                    val suggestionId = repo.completeWithSuggestion(
                        rule.rule.id,
                        AppComponent(ruleApp.packageName, ruleApp.activityName),
                        candidates
                    )
                    if (suggestionId > 0) {
                        fired.add(
                            FiredSuggestion(
                                suggestionId,
                                ruleApp.packageName,
                                ruleApp.activityName,
                                candidates.map { it.iconPackPackage }
                            )
                        )
                    }
                }
            }
        }

        fired
    }

    /**
     * Records the current icon state for a (newly created or edited) rule's pairs without
     * notifying, so a later pack update is measured against this baseline instead of being
     * swallowed as a "first sighting".
     */
    suspend fun baselineRule(ruleId: Long) = withContext(Dispatchers.Default) {
        val rule = repo.getRule(ruleId) ?: return@withContext
        val installedPacks = appMan.getIconPacks().associateBy { it.packageName }
        val packPackages = if (rule.rule.watchAllPacks) {
            installedPacks.keys.toList()
        } else {
            rule.packs.map { it.iconPackPackage }
        }

        for (ruleApp in rule.apps) {
            val installedApp = InstalledApplication(ruleApp.packageName, ruleApp.activityName, 0)
            for (packPackage in packPackages) {
                val pack = installedPacks[packPackage] ?: continue
                val (drawableName, hash) = resolveIcon(packPackage, installedApp)
                repo.upsertState(
                    WatchState(
                        packageName = ruleApp.packageName,
                        activityName = ruleApp.activityName,
                        iconPackPackage = packPackage,
                        lastPackVersionCode = pack.versionCode,
                        lastIconName = drawableName,
                        lastIconHash = hash,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** Resolves the (drawable name, content hash) a pack currently provides for an app, or nulls. */
    private fun resolveIcon(packPackage: String, installedApp: InstalledApplication): Pair<String?, String?> {
        val elements = appMan.getAppFilterRawElements(packPackage, listOf(installedApp))
        val component = installedApp.toComponentInfo()
        val drawableName = elements.filterIsInstance<RawItem>()
            .firstOrNull { it.component == component }?.drawableLink
        val resource = appMan.getDrawableFromAppFilterElements(packPackage, listOf(installedApp), elements)[installedApp]
        val hash = resource?.drawable?.toSafeBitmapOrNull()?.contentHash()
        return drawableName to hash
    }
}
