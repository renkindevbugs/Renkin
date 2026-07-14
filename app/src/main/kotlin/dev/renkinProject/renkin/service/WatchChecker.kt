package dev.renkinProject.renkin.service

import android.content.Context
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.BaselineInput
import dev.renkinProject.renkin.data.watch.CandidateInput
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchState
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.contentHash
import dev.renkinProject.renkin.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scans active watch rules and records a suggestion when a watched pack has published a
 * new icon for a watched app (see docs/icon-watch-feature.md, phase 3).
 *
 * Work is gated on each pack's versionCode: if the pack hasn't been updated since the
 * last check for an (app, pack) pair, it's skipped entirely — so a periodic run does
 * almost nothing unless a pack actually changed. Packs installed when a rule is created
 * are baselined by [saveRule] (no notification for icons that already existed); an
 * (app, pack) pair the checker has never seen — i.e. a pack installed AFTER the rule —
 * fires immediately when the pack carries an icon for the watched app, and a changed
 * icon content hash fires for known pairs.
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
        val packPackages: List<String>,
        // Profile owning the rule, so the notification can name it and deep-link into it.
        val profileId: Long
    )

    suspend fun runCheck(): List<FiredSuggestion> = withContext(Dispatchers.Default) {
        checkMutex.withLock { runCheckLocked() }
    }

    private suspend fun runCheckLocked(): List<FiredSuggestion> {
        val fired = mutableListOf<FiredSuggestion>()
        val installedPacks = watchablePacks()
        val installedAppsByPackage = appMan.getAllInstalledApplications().groupBy { it.packageName }

        for (rule in repo.getActiveRules()) {
            val packPackages = if (rule.rule.watchAllPacks) {
                installedPacks.keys.toList()
            } else {
                rule.packs.map { it.iconPackPackage }
            }

            for (ruleApp in rule.apps) {
                val packageActivities = installedAppsByPackage[ruleApp.packageName].orEmpty()
                val storedComponent = AppComponent(ruleApp.packageName, ruleApp.activityName)
                val exactApp = packageActivities.firstOrNull {
                    it.activityName == ruleApp.activityName
                }
                val activeComponent: AppComponent
                val installedApp: InstalledApplication
                if (exactApp != null) {
                    activeComponent = storedComponent
                    installedApp = exactApp
                } else {
                    // A package update may replace its launcher activity. Migrate only when the
                    // replacement is unambiguous; multiple activities require the user's choice.
                    val replacement = packageActivities.singleOrNull() ?: continue
                    val replacementApp = AppComponent(replacement.packageName, replacement.activityName)
                    val baseline = buildBaseline(
                        listOf(replacementApp), rule.rule.watchAllPacks, packPackages, installedPacks
                    )
                    val migrated = repo.migrateRuleApp(
                        rule.rule.id,
                        storedComponent,
                        replacementApp,
                        baseline
                    )
                    if (!migrated) continue
                    activeComponent = replacementApp
                    installedApp = replacement
                }
                val candidates = mutableListOf<CandidateInput>()

                for (packPackage in packPackages) {
                    val pack = installedPacks[packPackage] ?: continue
                    val previous = repo.getState(
                        rule.rule.id,
                        activeComponent.packageName,
                        activeComponent.activityName,
                        packPackage
                    )

                    // No update since last look → nothing to do (keeps periodic runs cheap)
                    if (previous != null && previous.lastPackVersionCode == pack.versionCode) continue

                    val (drawableName, hash) = resolveIcon(packPackage, installedApp)

                    // New for the user either way: a pack installed after the rule that already
                    // carries an icon for the app (previous == null — packs present at rule
                    // creation were baselined by saveRule), or a known pack whose icon
                    // content changed with an update.
                    val isNew = hash != null && (previous == null || hash != previous.lastIconHash)

                    repo.upsertState(
                        WatchState(
                            ruleId = rule.rule.id,
                            packageName = activeComponent.packageName,
                            activityName = activeComponent.activityName,
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
                        activeComponent,
                        candidates
                    )
                    if (suggestionId > 0) {
                        fired.add(
                            FiredSuggestion(
                                suggestionId,
                                activeComponent.packageName,
                                activeComponent.activityName,
                                candidates.map { it.iconPackPackage },
                                rule.rule.profileId
                            )
                        )
                    }
                }
            }
        }

        return fired
    }

    /**
     * Resolves a rule's current icons and commits the rule plus its baseline atomically. The same
     * mutex guards checks, so no worker can see the rule before its baseline exists.
     */
    suspend fun saveRule(
        existingRuleId: Long?,
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>,
        profileId: Long
    ): Long = withContext(Dispatchers.Default) {
        checkMutex.withLock {
            val baseline = buildBaseline(apps, watchAllPacks, packPackages)
            repo.saveRule(existingRuleId, apps, watchAllPacks, packPackages, profileId, baseline)
        }
    }

    private fun buildBaseline(
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        selectedPacks: List<String>,
        installedPacks: Map<String, IconPack> = watchablePacks()
    ): List<BaselineInput> {
        val packPackages = if (watchAllPacks) {
            installedPacks.keys.toList()
        } else {
            selectedPacks
        }
        val checkedAt = System.currentTimeMillis()
        val baseline = mutableListOf<BaselineInput>()
        val installedApps = apps.map { InstalledApplication(it.packageName, it.activityName, 0) }

        // Parsing appfilter.xml dominates save time. Resolve every selected app in one pass per
        // pack instead of reopening and reparsing the same pack once for every app.
        for (packPackage in packPackages) {
            val pack = installedPacks[packPackage] ?: continue
            val elements = appMan.getAppFilterRawElements(packPackage, installedApps)
            val resources = appMan.getDrawableFromAppFilterElements(packPackage, installedApps, elements)
            val drawableNames = mutableMapOf<String, String>()
            elements.filterIsInstance<RawItem>().forEach { item ->
                drawableNames.putIfAbsent(item.component, item.drawableLink)
            }

            for (installedApp in installedApps) {
                val drawableName = drawableNames[installedApp.toComponentInfo()]
                val hash = resources[installedApp]?.drawable?.toSafeBitmapOrNull()?.contentHash()
                baseline.add(
                    BaselineInput(
                        packageName = installedApp.packageName,
                        activityName = installedApp.activityName,
                        iconPackPackage = packPackage,
                        lastPackVersionCode = pack.versionCode,
                        lastIconName = drawableName,
                        lastIconHash = hash,
                        lastCheckedAt = checkedAt
                    )
                )
            }
        }
        return baseline
    }

    /**
     * Installed icon packs eligible as watch sources, keyed by package name. Renkin's own
     * generated packs are excluded — every profile's pack and the pre-rename legacy one (see
     * [IconPackBuilder.isOwnPack]): they only ever hold icons we just built, so they would
     * suggest the very icon the user already applied.
     */
    private fun watchablePacks() = appMan.getIconPacks()
        .filter { !IconPackBuilder.isOwnPack(it.packageName) }
        .associateBy { it.packageName }

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

    companion object {
        // Every entry point (periodic worker, one-shot worker, manual refresh and rule save)
        // lives in this process, so one shared lock prevents duplicate completion transactions.
        private val checkMutex = Mutex()
    }
}
