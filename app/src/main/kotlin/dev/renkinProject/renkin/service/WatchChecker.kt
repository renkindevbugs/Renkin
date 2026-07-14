package dev.renkinProject.renkin.service

import android.content.Context
import dev.renkinProject.renkin.data.InstalledApplication
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
    private val packageManager = context.packageManager

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

        for (rule in repo.getActiveRules()) {
            val packPackages = if (rule.rule.watchAllPacks) {
                installedPacks.keys.toList()
            } else {
                rule.packs.map { it.iconPackPackage }
            }

            for (ruleApp in rule.apps) {
                // Imported rules can watch apps this device doesn't have — leave those
                // dormant (no phantom suggestions for apps the user can't theme); the rule
                // comes alive by itself once the app is installed.
                if (!isAppInstalled(ruleApp.packageName)) continue
                val installedApp = InstalledApplication(ruleApp.packageName, ruleApp.activityName, 0)
                val candidates = mutableListOf<CandidateInput>()

                for (packPackage in packPackages) {
                    val pack = installedPacks[packPackage] ?: continue
                    val previous = repo.getState(rule.rule.id, ruleApp.packageName, ruleApp.activityName, packPackage)

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
        selectedPacks: List<String>
    ): List<BaselineInput> {
        val installedPacks = watchablePacks()
        val packPackages = if (watchAllPacks) {
            installedPacks.keys.toList()
        } else {
            selectedPacks
        }
        val checkedAt = System.currentTimeMillis()
        val baseline = mutableListOf<BaselineInput>()

        for (app in apps) {
            val installedApp = InstalledApplication(app.packageName, app.activityName, 0)
            for (packPackage in packPackages) {
                val pack = installedPacks[packPackage] ?: continue
                val (drawableName, hash) = resolveIcon(packPackage, installedApp)
                baseline.add(
                    BaselineInput(
                        packageName = app.packageName,
                        activityName = app.activityName,
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

    private fun isAppInstalled(packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

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
