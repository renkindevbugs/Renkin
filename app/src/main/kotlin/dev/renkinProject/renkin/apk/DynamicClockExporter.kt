package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RotateDrawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawDynamicClock
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.extension.getIdentifierByName
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.appFilterDrawableName
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PackageVersion

/**
 * Copies a source pack's live-clock icon (Nova/Lawnchair `<dynamic-clock>`) into the built
 * pack: hand artwork is captured without its rotation, which is exported separately as XML.
 * The copy only happens when the user's baked icon still looks
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
        val layers: List<ClockLayer>,
        val meta: RawDynamicClock
    )

    class ClockLayer(val bitmap: Bitmap, val rotation: Rotation? = null)

    class Rotation(val fromDegrees: Float, val toDegrees: Float, val pivotX: Float, val pivotY: Float)

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
        val drawableName = appFilterDrawableName(elements, component) ?: return null
        val meta = clocks.firstOrNull { it.drawableLink == drawableName } ?: return null

        val source = loadDrawable(sourcePack, drawableName) ?: return null
        val adaptive = if (PackageVersion.is26OrMore()) source as? AdaptiveIconDrawable else null
        val layered = (adaptive?.foreground ?: source) as? LayerDrawable ?: return null
        val activeHands = meta.handLayerIndices(layered.numberOfLayers)?.filter { it >= 0 } ?: return null

        // User-edit guard: the composite must still look like the pack's own clock —
        // otherwise the user's edited static icon wins and no clock is copied.
        if (adaptive != null) {
            val bakedAdaptive = bakedIcon as? AdaptiveIconPackDrawable ?: return null
            if (!render(layered).looksAlike(bakedAdaptive.foreground) ||
                !render(adaptive.background).looksAlike(bakedAdaptive.background)) return null
        } else {
            if (bakedIcon is AdaptiveIconPackDrawable || !render(layered).looksAlike(bakedIcon.toBitmap())) return null
        }

        // Resolve the source stack's insets/gravity once. Each captured bitmap keeps that placement
        // in a common layer-sized canvas, and rotations use pivots in the same coordinate space.
        val size = AdaptiveIconPackDrawable.LAYER_SIZE
        layered.setBounds(0, 0, size, size)
        val layers = (0 until layered.numberOfLayers).map { index ->
            val layer = layered.getDrawable(index)
            if (index in activeHands) {
                val rotating = layer as? RotateDrawable ?: return null
                val artwork = rotating.drawable ?: return null
                val bounds = artwork.bounds
                if (bounds.isEmpty || !Rect(0, 0, size, size).contains(bounds)) return null
                val pivotX = bounds.left + if (rotating.isPivotXRelative) rotating.pivotX * bounds.width() else rotating.pivotX
                val pivotY = bounds.top + if (rotating.isPivotYRelative) rotating.pivotY * bounds.height() else rotating.pivotY
                ClockLayer(
                    renderAtCurrentBounds(artwork, size),
                    Rotation(rotating.fromDegrees, rotating.toDegrees, pivotX / size, pivotY / size)
                )
            } else {
                ClockLayer(renderAtCurrentBounds(layer, size))
            }
        }
        return ClockIcon(layers, meta)
    }

    private fun loadDrawable(sourcePack: String, drawableName: String): Drawable? {
        val res = resourceResolver.getResources(sourcePack) ?: return null
        val id = res.getIdentifierByName(drawableName, "drawable", sourcePack)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null).mutate() }.getOrNull()
    }

    private fun render(drawable: Drawable, size: Int = 256): Bitmap {
        drawable.setBounds(0, 0, size, size)
        return renderAtCurrentBounds(drawable, size)
    }

    private fun renderAtCurrentBounds(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
