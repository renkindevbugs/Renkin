package dev.renkinProject.renkin.service

import android.content.Context
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.CandidateInput
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchState
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.contentHash
import dev.renkinProject.renkin.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans active watch rules and records a suggestion when a watched pack has published a
 * new icon for a watched app (see docs/icon-watch-feature.md, phase 3).
 *
 * Work is gated on each pack's versionCode: if the pack hasn't been updated since the
 * last check for an (app, pack) pair, it's skipped entirely — so a periodic run does
 * almost nothing unless a pack actually changed. Packs installed when a rule is created
 * are baselined by [baselineRule] (no notification for icons that already existed); an
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
                    val previous = repo.getState(ruleApp.packageName, ruleApp.activityName, packPackage)

                    // No update since last look → nothing to do (keeps periodic runs cheap)
                    if (previous != null && previous.lastPackVersionCode == pack.versionCode) continue

                    val (drawableName, hash) = resolveIcon(packPackage, installedApp)

                    // New for the user either way: a pack installed after the rule that already
                    // carries an icon for the app (previous == null — packs present at rule
                    // creation were baselined by baselineRule), or a known pack whose icon
                    // content changed with an update.
                    val isNew = hash != null && (previous == null || hash != previous.lastIconHash)

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
                                candidates.map { it.iconPackPackage },
                                rule.rule.profileId
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
        val installedPacks = watchablePacks()
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
}
