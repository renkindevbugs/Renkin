package dev.renkinProject.renkin.service

import android.content.Context
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.BaselineInput
import dev.renkinProject.renkin.data.watch.CandidateInput
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dev.renkinProject.renkin.data.watch.IconSuggestionCandidate
import dev.renkinProject.renkin.data.watch.WatchState
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.contentHash
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.appFilterDrawableName
import dev.renkinProject.renkin.packages.appFilterDrawableNames
import dev.renkinProject.renkin.packages.InstalledAppCatalog
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Isolates one third-party pack read without caching failures. Callers can therefore skip the
 * current pass and retry the same version later, while coroutine cancellation still propagates.
 */
internal inline fun <T> readWatchPackOrNull(
    packPackage: String,
    onFailure: (Exception) -> Unit = {
        Log.error("WatchChecker", "Skipping unreadable pack $packPackage", it)
    },
    read: () -> T
): T? {
    return try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        null
    }
}

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
class WatchChecker(
    context: Context,
    private val iconPackCatalog: IconPackCatalog = IconPackCatalog(context),
    private val repo: WatchRepository = WatchRepository(context)
) {
    private val appMan = ApplicationManager(context)
    private val installedAppCatalog = InstalledAppCatalog(context)

    data class FiredSuggestion(
        val suggestionId: Long,
        val packageName: String,
        val activityName: String,
        val packPackages: List<String>,
        // Profile owning the rule, so the notification can name it and deep-link into it.
        val profileId: Long
    )

    private suspend fun <T> withStorageAccess(block: suspend () -> T): T = StorageOperationGate.run {
        withContext(Dispatchers.Default) { block() }
    }

    data class CheckResult(val suggestions: List<FiredSuggestion>, val storageEpoch: Long) {
        // Check under the storage gate together with notification/metadata side effects.
        fun isCurrent(): Boolean = storageEpoch == StorageOperationGate.epoch
    }

    private data class RuleSnapshot(
        val details: RuleWithDetails,
        val states: Set<WatchState>,
        val candidates: Map<Long, Set<IconSuggestionCandidate>>
    )

    private data class AppCheck(
        val component: AppComponent,
        val previousComponent: AppComponent? = null,
        val baseline: List<BaselineInput> = emptyList(),
        val states: List<WatchState> = emptyList(),
        val candidates: List<CandidateInput> = emptyList()
    )

    private data class CompletedCheck(val suggestionId: Long, val candidates: List<CandidateInput>)
    private data class RuleCheck(
        val snapshot: RuleSnapshot,
        val apps: List<AppCheck> = emptyList(),
        val completed: List<CompletedCheck> = emptyList()
    )

    private suspend fun snapshot(rule: RuleWithDetails) = RuleSnapshot(
        rule,
        repo.getStatesForRule(rule.rule.id).toSet(),
        rule.suggestions.associate { it.id to repo.getCandidates(it.id).toSet() }
    )

    private suspend fun unchanged(before: RuleSnapshot): Boolean {
        val current = repo.getRule(before.details.rule.id) ?: return false
        return current.rule == before.details.rule &&
            current.apps.toSet() == before.details.apps.toSet() &&
            current.packs.toSet() == before.details.packs.toSet() &&
            current.suggestions.toSet() == before.details.suggestions.toSet() &&
            repo.getStatesForRule(current.rule.id).toSet() == before.states &&
            current.suggestions.all { repo.getCandidates(it.id).toSet() == before.candidates[it.id] }
    }

    suspend fun runCheck(refreshCompletedSuggestions: Boolean = false): List<FiredSuggestion> =
        runCheckResult(refreshCompletedSuggestions).suggestions

    suspend fun runCheckResult(refreshCompletedSuggestions: Boolean = false): CheckResult = withContext(Dispatchers.Default) {
        val (epoch, rules) = StorageOperationGate.run {
            StorageOperationGate.epoch to repo.getAllRules()
                .filter { !it.rule.completed || refreshCompletedSuggestions }
                .map { snapshot(it) }
        }
        // Package parsing and bitmap hashes never own the global storage gate.
        val installedPacks = watchablePacks()
        val installedApps = installedAppCatalog.getAllInstalledApplications().groupBy { it.packageName }
        val packIcons = packIconsFor(rules, installedApps)
        val checks = rules.map { before ->
            if (before.details.rule.completed) planCompleted(before, installedPacks, installedApps, packIcons)
            else planActive(before, installedPacks, installedApps, packIcons)
        }
        val fired = mutableListOf<FiredSuggestion>()
        for (check in checks) {
            // Published only after the transaction returns: a rolled-back commit must not notify.
            fired += repo.commitCheckedRule {
                val committed = mutableListOf<FiredSuggestion>()
                if (StorageOperationGate.epoch != epoch || !unchanged(check.snapshot)) return@commitCheckedRule committed
                val rule = check.snapshot.details.rule
                val states = check.apps.flatMap { it.states }
                if (states.isNotEmpty()) repo.upsertStates(states)
                for (app in check.apps) {
                    if (app.previousComponent != null) {
                        repo.migrateRuleApp(rule.id, app.previousComponent, app.component, app.baseline)
                        continue
                    }
                    if (app.candidates.isNotEmpty()) {
                        val id = repo.completeWithSuggestion(rule.id, app.component, app.candidates)
                        if (id > 0) committed += FiredSuggestion(
                            suggestionId = id,
                            packageName = app.component.packageName,
                            activityName = app.component.activityName,
                            packPackages = app.candidates.map { it.iconPackPackage },
                            profileId = rule.profileId
                        )
                    }
                }
                for (completed in check.completed) {
                    val suggestion = check.snapshot.details.suggestions.first { it.id == completed.suggestionId }
                    if (repo.replaceCandidates(suggestion.id, completed.candidates)) {
                        committed += FiredSuggestion(
                            suggestionId = suggestion.id,
                            packageName = suggestion.packageName,
                            activityName = suggestion.activityName,
                            packPackages = completed.candidates.map { it.iconPackPackage },
                            profileId = rule.profileId
                        )
                    }
                }
                committed
            }
        }
        CheckResult(fired, epoch)
    }

    private fun planActive(
        before: RuleSnapshot,
        installedPacks: Map<String, IconPack>,
        installedApps: Map<String, List<InstalledApplication>>,
        packIcons: WatchPackIcons
    ): RuleCheck {
        val rule = before.details
        val packs = if (rule.rule.watchAllPacks) installedPacks.keys.toList() else rule.packs.map { it.iconPackPackage }
        val statesByComponentPack = before.states.associateBy {
            Triple(it.packageName, it.activityName, it.iconPackPackage)
        }
        val apps = rule.apps.mapNotNull { ruleApp ->
            val component = AppComponent(ruleApp.packageName, ruleApp.activityName)
            val resolved = resolveWatchApp(component, installedApps[ruleApp.packageName].orEmpty()) ?: return@mapNotNull null
            val installedApp = resolved.application
            if (resolved.componentChanged) {
                val replacement = AppComponent(installedApp.packageName, installedApp.activityName)
                return@mapNotNull AppCheck(replacement, component,
                    buildBaseline(listOf(replacement), rule.rule.watchAllPacks, packs, installedPacks))
            }
            val states = mutableListOf<WatchState>()
            val candidates = mutableListOf<CandidateInput>()
            for (packPackage in packs) {
                val pack = installedPacks[packPackage] ?: continue
                val previous = statesByComponentPack[Triple(component.packageName, component.activityName, packPackage)]
                if (previous?.lastPackVersionCode == pack.versionCode) continue
                val (drawableName, hash) = packIcons.resolveIcon(packPackage, installedApp) ?: continue
                states += WatchState(
                    ruleId = rule.rule.id,
                    packageName = component.packageName,
                    activityName = component.activityName,
                    iconPackPackage = packPackage,
                    lastPackVersionCode = pack.versionCode,
                    lastIconName = drawableName,
                    lastIconHash = hash,
                    lastCheckedAt = System.currentTimeMillis()
                )
                if (hash != null && drawableName != null && (previous == null || hash != previous.lastIconHash)) {
                    candidates += CandidateInput(packPackage, drawableName, hash)
                }
            }
            AppCheck(component, states = states, candidates = candidates)
        }
        return RuleCheck(before, apps = apps)
    }

    private fun planCompleted(
        before: RuleSnapshot,
        installedPacks: Map<String, IconPack>,
        installedApps: Map<String, List<InstalledApplication>>,
        packIcons: WatchPackIcons
    ): RuleCheck {
        val completed = before.details.suggestions.mapNotNull { suggestion ->
            val app = installedApps[suggestion.packageName].orEmpty().firstOrNull { it.activityName == suggestion.activityName }
                ?: return@mapNotNull null
            val stored = before.candidates[suggestion.id].orEmpty()
            val candidates = stored.map { candidate ->
                val resolved = if (candidate.iconPackPackage in installedPacks) packIcons.resolveIcon(candidate.iconPackPackage, app) else null
                val name = resolved?.first
                val hash = resolved?.second
                if (name != null && hash != null) CandidateInput(candidate.iconPackPackage, name, hash)
                else CandidateInput(candidate.iconPackPackage, candidate.drawableName, candidate.iconHash)
            }
            val old = stored.map { CandidateInput(it.iconPackPackage, it.drawableName, it.iconHash) }.toSet()
            if (candidates.toSet() == old) null else CompletedCheck(suggestion.id, candidates)
        }
        return RuleCheck(before, completed = completed)
    }

    suspend fun saveRule(
        existingRuleId: Long?,
        apps: List<AppComponent>,
        watchAllPacks: Boolean,
        packPackages: List<String>,
        profileId: Long,
        commit: suspend (suspend () -> Long) -> Long = ::commitRuleDirectly
    ): Long = withContext(Dispatchers.Default) {
        val (epoch, previous) = StorageOperationGate.run {
            StorageOperationGate.epoch to existingRuleId?.let { repo.getRule(it)?.let { rule -> snapshot(rule) } }
        }
        if (existingRuleId != null && previous == null) return@withContext 0L
        val baseline = buildBaseline(apps, watchAllPacks, packPackages)
        StorageOperationGate.run {
            if (epoch != StorageOperationGate.epoch || (previous != null && !unchanged(previous))) return@run 0L
            commit { repo.saveRule(existingRuleId, apps, watchAllPacks, packPackages, profileId, baseline) }
        }
    }

    // Used only inside an import commit, where all live stores must remain frozen.
    suspend fun initializeMissingBaselines() = withStorageAccess {
        val installedPacks = watchablePacks()
        for (rule in repo.getActiveRules()) {
            val existing = repo.getStatesForRule(rule.rule.id).mapTo(mutableSetOf()) {
                Triple(it.packageName, it.activityName, it.iconPackPackage)
            }
            val baseline = buildBaseline(
                apps = rule.apps.map { AppComponent(it.packageName, it.activityName) },
                watchAllPacks = rule.rule.watchAllPacks,
                selectedPacks = rule.packs.map { it.iconPackPackage },
                installedPacks = installedPacks
            ).filterNot { Triple(it.packageName, it.activityName, it.iconPackPackage) in existing }
            repo.upsertStates(rule.rule.id, baseline)
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
            // A third-party pack with a malformed appfilter.xml throws while parsing. Skip that
            // pack instead of failing the whole baseline — the same isolation IconPackRepository
            // applies when loading packs.
            val parsed = readWatchPackOrNull(packPackage) {
                val elements = appMan.getAppFilterRawElements(packPackage, installedApps)
                elements to appMan.getDrawableFromAppFilterElements(
                    packPackage, installedApps, elements
                )
            } ?: continue
            val (elements, resources) = parsed
            val drawableNames = appFilterDrawableNames(elements)

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
    private fun watchablePacks() = iconPackCatalog.installedIconPacks()
        .filter { !IconPackBuilder.isOwnPack(it.packageName) }
        .associateBy { it.packageName }

    // Every installed activity of a watched package, so a migrated launcher activity is covered too.
    private fun packIconsFor(
        rules: List<RuleSnapshot>,
        installedApps: Map<String, List<InstalledApplication>>
    ): WatchPackIcons {
        val watchedPackages = rules.flatMapTo(HashSet()) { snapshot ->
            snapshot.details.apps.map { it.packageName } + snapshot.details.suggestions.map { it.packageName }
        }
        return WatchPackIcons(
            watchedApps = watchedPackages.flatMap { installedApps[it].orEmpty() },
            parse = appMan::getAppFilterRawElements,
            resolve = ::resolveIcon
        )
    }

    /**
     * The (drawable name, content hash) a pack's parsed appfilter gives one app. The hash must stay
     * byte-identical to the stored baselines, or every pair would fire a false suggestion.
     */
    private fun resolveIcon(
        packPackage: String,
        installedApp: InstalledApplication,
        elements: List<RawElement>
    ): Pair<String?, String?> {
        val drawableName = appFilterDrawableName(elements, installedApp.toComponentInfo())
        val hash = appMan.getDrawableFromAppFilterElements(packPackage, installedApp, elements)
            ?.drawable?.toSafeBitmapOrNull()?.contentHash()
        return drawableName to hash
    }

}

private suspend fun commitRuleDirectly(operation: suspend () -> Long): Long = operation()
