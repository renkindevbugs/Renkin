package dev.renkinProject.renkin.service

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo

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
    private val resolve: (packPackage: String, app: InstalledApplication, elements: List<RawElement>) -> Pair<String?, String?>
) {
    private val watchedApps = watchedApps.distinctBy { it.toComponentInfo() }
    private val watchedComponents = this.watchedApps.mapTo(HashSet()) { it.toComponentInfo() }
    // Null values are remembered too: an unreadable pack is reported once per run, not per app.
    private val elementsByPack = HashMap<String, List<RawElement>?>()
    private val iconsByPackComponent = HashMap<Pair<String, String>, Pair<String?, String?>?>()

    fun resolveIcon(packPackage: String, app: InstalledApplication): Pair<String?, String?>? {
        val key = packPackage to app.toComponentInfo()
        if (key in iconsByPackComponent) return iconsByPackComponent[key]
        val elements = elementsFor(packPackage, app)
        val icon = if (elements == null) null else readWatchPackOrNull(packPackage) { resolve(packPackage, app, elements) }
        iconsByPackComponent[key] = icon
        return icon
    }

    // The parse keeps only the listed components, so an app outside the run's set gets its own read.
    private fun elementsFor(packPackage: String, app: InstalledApplication): List<RawElement>? {
        if (app.toComponentInfo() !in watchedComponents) {
            return readWatchPackOrNull(packPackage) { parse(packPackage, listOf(app)) }
        }
        if (packPackage !in elementsByPack) {
            elementsByPack[packPackage] = readWatchPackOrNull(packPackage) { parse(packPackage, watchedApps) }
        }
        return elementsByPack[packPackage]
    }
}
