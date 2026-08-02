package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawDynamicClock
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.extension.getIdentifierByName
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PackageVersion
import kotlin.math.abs

/**
 * Copies a source pack's live-clock icon (Nova/Lawnchair `<dynamic-clock>`) into the built
 * pack: the icon is a layer stack whose hour/minute hand layers a supporting launcher
 * rotates to the real time. The copy only happens when the user's baked icon still looks
 * like the pack's plain drawable — a colorized, shaped or otherwise edited clock stays the
 * static image the user made. Launchers without dynamic-clock support draw the copied layer
 * stack as-is (hands at the authored default), so the fallback needs no extra work.
 */
class DynamicClockExporter(
    private val context: Context,
    // Every buildable app up front, so a pack's appfilter is parsed once, not per app.
    private val allApps: List<InstalledApplication>
) {
    private val resourceResolver by lazy { PackageResourceResolver(context) }
    private val appManager by lazy { ApplicationManager(context, resourceResolver) }
    private val packElements = mutableMapOf<String, List<RawElement>>()

    class ClockIcon(
        val layers: List<Bitmap>,
        val meta: RawDynamicClock
    )

    /**
     * The live-clock icon [sourcePack] declares for [app]'s component, with its layers
     * rendered out — or null when the pack declares none, isn't installed, the drawable
     * isn't a layer stack, or [bakedIcon] no longer matches the pack's plain drawable.
     */
    fun clockIconFor(app: PackageInfoStruct, sourcePack: String, bakedIcon: IconPackDrawable): ClockIcon? {
        val elements = packElements.getOrPut(sourcePack) {
            runCatching { appManager.getAppFilterRawElements(sourcePack, allApps) }.getOrDefault(emptyList())
        }
        val clocks = elements.filterIsInstance<RawDynamicClock>()
        if (clocks.isEmpty()) return null

        val component = app.toInstalledApplication().toComponentInfo()
        val drawableName = elements.filterIsInstance<RawItem>()
            .firstOrNull { it.component == component }?.drawableLink ?: return null
        val meta = clocks.firstOrNull { it.drawableLink == drawableName } ?: return null

        val hourIndex = meta.hourLayerIndex.toIntOrNull() ?: return null
        val minuteIndex = meta.minuteLayerIndex.toIntOrNull() ?: return null

        val layered = loadLayeredDrawable(sourcePack, drawableName) ?: return null
        if (hourIndex !in 0 until layered.numberOfLayers) return null
        if (minuteIndex !in 0 until layered.numberOfLayers) return null

        // User-edit guard: the composite must still look like the pack's own clock —
        // otherwise the user's edited static icon wins and no clock is copied.
        if (!looksAlike(render(layered), bakedIcon.toBitmap())) return null

        val layers = (0 until layered.numberOfLayers).map { index ->
            render(layered.getDrawable(index))
        }
        return ClockIcon(layers, meta)
    }

    /** The pack's drawable as a layer stack (unwrapping an adaptive foreground), or null. */
    private fun loadLayeredDrawable(sourcePack: String, drawableName: String): LayerDrawable? {
        val res = resourceResolver.getResources(sourcePack) ?: return null
        val id = res.getIdentifierByName(drawableName, "drawable", sourcePack)
        if (id == 0) return null
        val drawable = runCatching { res.getDrawable(id, null) }.getOrNull() ?: return null
        val unwrapped = if (PackageVersion.is26OrMore() && drawable is AdaptiveIconDrawable) {
            drawable.foreground
        } else drawable
        return unwrapped as? LayerDrawable
    }

    private fun render(drawable: Drawable, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Downscaled per-channel comparison with a small tolerance — the generation pipeline
     * may rescale/re-encode, so exact equality would reject untouched icons too.
     */
    private fun looksAlike(a: Bitmap, b: Bitmap): Boolean {
        val n = 32
        val sa = Bitmap.createScaledBitmap(a, n, n, true)
        val sb = Bitmap.createScaledBitmap(b, n, n, true)
        var diff = 0L
        for (y in 0 until n) {
            for (x in 0 until n) {
                val pa = sa.getPixel(x, y)
                val pb = sb.getPixel(x, y)
                diff += abs(Color.alpha(pa) - Color.alpha(pb)) +
                    abs(Color.red(pa) - Color.red(pb)) +
                    abs(Color.green(pa) - Color.green(pb)) +
                    abs(Color.blue(pa) - Color.blue(pb))
            }
        }
        // Average difference per pixel across the four channels.
        return diff / (n * n * 4) < 8
    }
}
