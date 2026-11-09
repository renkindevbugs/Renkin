package dev.renkinProject.renkin.service

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.data.watch.BaselineInput
import dev.renkinProject.renkin.data.watch.WatchReadFailureReason
import dev.renkinProject.renkin.data.watch.WatchReadIssue
import dev.renkinProject.renkin.packages.appFilterDrawableName
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException

/**
 * One check run's pack reads: each pack's appfilter is parsed once for all [watchedApps] instead
 * of once per (app, pack) pair. It lives for a single run, so a pack that failed to read is retried
 * by the next run, as [readWatchPackOrNull] promises.
 *
 * [resolveIcon] keeps the old per-pair contract: (drawable name, content hash), a pair of nulls
 * when the pack has no icon for the app, and null when the pack could not be read.
 */
internal class WatchPackIcons(
    watchedApps: List<InstalledApplication>,
    private val parse: (packPackage: String, apps: List<InstalledApplication>) -> List<RawElement>,
    private val resolve: (packPackage: String, app: InstalledApplication, elements: List<RawElement>) -> Pair<String?, String?>,
    private val describeFailure: (packPackage: String, app: InstalledApplication, drawableName: String) -> WatchReadFailureReason =
        { _, _, _ -> WatchReadFailureReason.ICON_UNREADABLE }
) {
    private val watchedApps = watchedApps.distinctBy { it.toComponentInfo() }
    private val watchedComponents = this.watchedApps.mapTo(HashSet()) { it.toComponentInfo() }
    // Null values are remembered too: an unreadable pack is reported once per run, not per app.
    private val elementsByPack = HashMap<String, List<RawElement>?>()
    private val iconsByPackComponent = HashMap<Pair<String, String>, Pair<String?, String?>?>()
    var hadReadFailures: Boolean = false
        private set
    private val unreadablePacks = linkedSetOf<String>()
    val failedPackages: Set<String> get() = unreadablePacks.toSet()
    private val issues = linkedSetOf<WatchReadIssue>()
    val readIssues: List<WatchReadIssue> get() = issues.toList()

    fun markReadFailure(packPackage: String) {
        markReadIssue(WatchReadIssue(packPackage, WatchReadFailureReason.PACK_UNREADABLE))
    }

    fun markReadIssue(issue: WatchReadIssue) {
        hadReadFailures = true
        unreadablePacks += issue.packPackage
        issues += issue
    }

    private inline fun <T> readPack(
        packPackage: String,
        app: InstalledApplication? = null,
        drawableName: String? = null,
        read: () -> T
    ): T? =
        readWatchPackOrNull(packPackage, onFailure = {
            markReadIssue(WatchReadIssue(
                packPackage,
                if (app == null) WatchReadFailureReason.PACK_UNREADABLE else WatchReadFailureReason.ICON_UNREADABLE,
                app?.packageName, app?.activityName, drawableName
            ))
            Log.error("WatchChecker", "Skipping unreadable pack $packPackage", it)
        }, read = read)

    fun resolveIcon(packPackage: String, app: InstalledApplication): Pair<String?, String?>? {
        val key = packPackage to app.toComponentInfo()
        if (key in iconsByPackComponent) return iconsByPackComponent[key]
        val elements = elementsFor(packPackage, app)
        val mappedName = elements?.let { appFilterDrawableName(it, app.toComponentInfo()) }
        val resolved = if (elements == null) null else readPack(packPackage, app, mappedName) {
            resolve(packPackage, app, elements)
        }
        val icon = if (resolved != null && resolved.first != null && resolved.second == null) {
            // A mapped icon that could not render must remain retryable at the same pack version.
            val name = requireNotNull(resolved.first)
            val reason = try {
                describeFailure(packPackage, app, name)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WatchReadFailureReason.ICON_UNREADABLE
            }
            markReadIssue(WatchReadIssue(packPackage, reason, app.packageName, app.activityName, name))
            Log.error("WatchChecker", "Mapped icon unreadable: pack=$packPackage, component=${app.toComponentInfo()}, drawable=${resolved.first}")
            null
        } else resolved
        iconsByPackComponent[key] = icon
        return icon
    }

    fun baseline(pack: IconPack, checkedAt: Long): List<BaselineInput> = watchedApps.mapNotNull { app ->
        val (name, hash) = resolveIcon(pack.packageName, app) ?: return@mapNotNull null
        BaselineInput(app.packageName, app.activityName, pack.packageName, pack.versionCode, name, hash, checkedAt)
    }

    // The parse keeps only the listed components, so an app outside the run's set gets its own read.
    private fun elementsFor(packPackage: String, app: InstalledApplication): List<RawElement>? {
        if (app.toComponentInfo() !in watchedComponents) {
            return readPack(packPackage) { parse(packPackage, listOf(app)) }
        }
        if (packPackage !in elementsByPack) {
            elementsByPack[packPackage] = readPack(packPackage) { parse(packPackage, watchedApps) }
        }
        return elementsByPack[packPackage]
    }
}
