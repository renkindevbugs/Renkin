package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.LayerDrawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawDynamicClock
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.getIdentifierByName
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.PackageVersion
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
class DynamicClockPreview(private val context: Context) {
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
    fun storedSourceFor(app: PackageInfoStruct): DynamicClockSource? {
        val pack = app.sourcePackName?.takeIf { it.isNotEmpty() } ?: return null
        val icon = app.createdIcon ?: return null
        val installed = app.toInstalledApplication()
        DynamicClockExporter(context, listOf(installed)).clockIconFor(app, pack, icon) ?: return null
        return mappedClock(installed, pack)
    }

    private fun mappedClock(app: InstalledApplication, packPackageName: String): DynamicClockSource? {
        val elements = runCatching { appManager.getAppFilterRawElements(packPackageName, listOf(app)) }
            .getOrDefault(emptyList())
        val drawableName = appFilterDrawableName(elements, app.toComponentInfo()) ?: return null
        val meta = elements.filterIsInstance<RawDynamicClock>().firstOrNull { it.drawableLink == drawableName }
            ?: return null
        return DynamicClockSource(packPackageName, drawableName, meta)
    }

    fun render(source: DynamicClockSource, hour: Int, minute: Int): IconPackDrawable? {
        val res = resourceResolver.getResources(source.packPackageName) ?: return null
        val id = res.getIdentifierByName(source.drawableName, "drawable", source.packPackageName)
        val drawable = resourceResolver.getDrawable(res, id)?.mutate() ?: return null
        val adaptive = if (PackageVersion.is26OrMore()) drawable as? AdaptiveIconDrawable else null
        val layered = (adaptive?.foreground ?: drawable) as? LayerDrawable ?: return null
        val hands = source.meta.handLayerIndices(layered.numberOfLayers) ?: return null
        source.meta.handLevels(hour, minute).forEachIndexed { hand, level ->
            hands[hand].takeIf { it >= 0 }?.let { layered.getDrawable(it).level = level }
        }
        return if (adaptive != null) AdaptiveIconPackDrawable.capture(adaptive)
        else drawable.toSafeBitmapOrNull(CLOCK_RENDER_SIZE, CLOCK_RENDER_SIZE)?.let { BitmapIconDrawable(resources, it) }
    }

    private companion object {
        const val CLOCK_RENDER_SIZE = 500
    }
}
