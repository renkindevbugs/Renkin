package dev.renkinProject.renkin.apk

import android.content.Context
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawDynamicClock
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.appFilterDrawableName

/** Hour, minute and second layer indices (-1 = absent), or null when the declaration is unusable. */
internal fun RawDynamicClock.handLayerIndices(layerCount: Int): List<Int>? {
    if (defaultHour.toIntOrNull() !in 0..23 ||
        defaultMinute.toIntOrNull() !in 0..59 ||
        defaultSecond.toIntOrNull() !in 0..59) return null
    val hands = listOf(hourLayerIndex, minuteLayerIndex, secondLayerIndex).map { it.toIntOrNull() ?: return null }
    if (hands.any { it < -1 || it >= layerCount }) return null
    val active = hands.filter { it >= 0 }
    if (active.isEmpty() || active.distinct().size != active.size) return null
    return hands
}

/**
 * Hour, minute and second RotateDrawable levels at [hour]:[minute]:00, the same levels as AOSP
 * Launcher3's ClockDrawableWrapper, which `<dynamic-clock>` packs target. Each RotateDrawable maps
 * its level onto the pack's own from/to degrees. Expects a declaration [handLayerIndices] accepted.
 */
internal fun RawDynamicClock.handLevels(hour: Int, minute: Int): List<Int> {
    val second = 0
    val hour12 = hour % 12
    val convertedHour = (hour12 + (12 - defaultHour.toInt() % 12)) % 12
    val convertedMinute = (minute + (60 - defaultMinute.toInt())) % 60
    val convertedSecond = (second + (60 - defaultSecond.toInt())) % 60
    return listOf(convertedHour * 60 + minute, convertedMinute + hour12 * 60, convertedSecond * 10)
}

/** A pack's live-clock drawable that the built pack will export with rotating hands. */
data class DynamicClockSource(val packPackageName: String, val drawableName: String, val meta: RawDynamicClock)

/**
 * Renders a `<dynamic-clock>` icon at a chosen time the way a launcher does, so the editor can
 * show how the hands sit at awkward times before building.
 */
class DynamicClockPreview(
    private val context: Context,
    // The startup appfilter index; a pack or component missing from it falls back to a parse.
    private val indexedElements: (String, List<InstalledApplication>) -> List<RawElement>? = { _, _ -> null }
) {
    private val resourceResolver = PackageResourceResolver(context)
    private val appManager = ApplicationManager(context, resourceResolver)
    private val resources = context.resources

    /**
     * The clock [packPackageName]'s drawable [resourceId] declares for [app], or null when it is
     * not a live clock or the pack maps a different drawable to [app]: the export copies the
     * rotation only for the component's own mapping.
     */
    fun sourceFor(app: InstalledApplication, packPackageName: String, resourceId: Int): DynamicClockSource? {
        val res = resourceResolver.getResources(packPackageName) ?: return null
        val drawableName = runCatching { res.getResourceEntryName(resourceId) }.getOrNull() ?: return null
        return mappedClock(app, packPackageName)?.takeIf { it.drawableName == drawableName }
    }

    /** The live clock an already applied icon exports, decided by the same check as the build. */
    fun storedSourceFor(
        app: PackageInfoStruct,
        stageChains: (PackageInfoStruct) -> List<List<GenerationOptions>>
    ): DynamicClockSource? {
        val pack = app.sourcePackName?.takeIf { it.isNotEmpty() } ?: return null
        val icon = app.createdIcon ?: return null
        val installed = app.toInstalledApplication()
        DynamicClockExporter(context, listOf(installed), stageChains, indexedElements)
            .clockIconFor(app, pack, icon) ?: return null
        return mappedClock(installed, pack)
    }

    private fun mappedClock(app: InstalledApplication, packPackageName: String): DynamicClockSource? {
        val component = app.toComponentInfo()
        val elements = indexedElements(packPackageName, listOf(app))
            ?: runCatching { appManager.getAppFilterRawElements(packPackageName, listOf(app)) }.getOrDefault(emptyList())
        val drawableName = appFilterDrawableName(elements, component) ?: return null
        val meta = elements.filterIsInstance<RawDynamicClock>().firstOrNull { it.drawableLink == drawableName }
            ?: return null
        return DynamicClockSource(packPackageName, drawableName, meta)
    }

    /**
     * [source]'s layers styled by [chain] the way the build styles them, or null when the result
     * does not reproduce [frame], the icon the editor shows: the strip must not promise times the
     * built pack would not show.
     */
    fun styledClock(source: DynamicClockSource, chain: List<GenerationOptions>, frame: IconPackDrawable?): PreviewClock? {
        val captured = DynamicClockExporter(context, emptyList())
            .captureClock(source.packPackageName, source.drawableName, source.meta) ?: return null
        if (captured.background == null) return PreviewClock(captured.icon, null).takeIf { chain.isEmpty() }
        val adaptiveFrame = frame as? AdaptiveIconPackDrawable ?: return null
        val styled = if (chain.isEmpty()) captured.icon else captured.icon.styledWith(resources, chain) ?: return null
        if (!styled.authoredForeground().looksAlike(adaptiveFrame.foreground)) return null
        return PreviewClock(styled, adaptiveFrame)
    }

    fun frameAt(clock: PreviewClock, hour: Int, minute: Int): IconPackDrawable {
        val foreground = clock.icon.foregroundAt(hour, minute)
        return clock.frame?.withForeground(foreground) ?: BitmapIconDrawable(resources, foreground)
    }
}

/** A clock ready to preview: its styled layers and the editor icon whose foreground they replace. */
class PreviewClock(internal val icon: DynamicClockExporter.ClockIcon, internal val frame: AdaptiveIconPackDrawable?)
