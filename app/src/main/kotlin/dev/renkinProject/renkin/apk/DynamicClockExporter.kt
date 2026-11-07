package dev.renkinProject.renkin.apk

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RotateDrawable
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawDynamicClock
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.extension.getIdentifierByName
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconImageEditPipeline
import dev.renkinProject.renkin.icon.creator.TransformModifier
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.packages.appFilterDrawableName

/**
 * Copies a source pack's live-clock icon (Nova/Lawnchair `<dynamic-clock>`) into the built
 * pack: hand artwork is captured without its rotation, which is exported separately as XML.
 * A clock whose baked icon still looks like the pack's plain drawable is copied as is. A modified
 * adaptive clock is copied when its modifiers work layer by layer ([keepsClockLayers]) and one
 * candidate stage chain from [stageChains] re-renders the baked foreground; anything else stays
 * the static image the user made. Launchers without dynamic-clock support draw the copied layer
 * stack as-is (hands at the authored default), so the fallback needs no extra work.
 */
class DynamicClockExporter(
    private val context: Context,
    // Every buildable app up front, so a pack's appfilter is parsed once, not per app.
    private val allApps: List<InstalledApplication>,
    private val stageChains: (PackageInfoStruct) -> List<List<GenerationOptions>> = { emptyList() },
    private val indexedElements: (String, List<InstalledApplication>) -> List<RawElement>? = { _, _ -> null }
) {
    private val resourceResolver by lazy { PackageResourceResolver(context) }
    private val appManager by lazy { ApplicationManager(context, resourceResolver) }
    private val packElements = mutableMapOf<String, List<RawElement>>()

    class ClockIcon(
        val layers: List<ClockLayer>,
        val meta: RawDynamicClock
    ) {
        private val hands = meta.handLayerIndices(layers.size).orEmpty()

        /** The foreground as a launcher draws it at [hour]:[minute]. */
        fun foregroundAt(hour: Int, minute: Int): Bitmap = compose(meta.handLevels(hour, minute))

        /** The foreground at level 0, the pose the pack's static drawable shows. */
        fun authoredForeground(): Bitmap = compose(listOf(0, 0, 0))

        private fun compose(levels: List<Int>): Bitmap {
            val size = layers.first().bitmap.width
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.density = Bitmap.DENSITY_NONE
            val canvas = Canvas(bitmap)
            layers.forEachIndexed { index, layer ->
                canvas.save()
                layer.rotation?.let { rotation ->
                    val level = levels.getOrElse(hands.indexOf(index)) { 0 }
                    canvas.rotate(rotation.degreesAt(level), rotation.pivotX * size, rotation.pivotY * size)
                }
                canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
                canvas.restore()
            }
            return bitmap
        }
    }

    class ClockLayer(val bitmap: Bitmap, val rotation: Rotation? = null)

    class Rotation(val fromDegrees: Float, val toDegrees: Float, val pivotX: Float, val pivotY: Float) {
        // RotateDrawable maps its 0..10000 level linearly onto from..to degrees.
        fun degreesAt(level: Int): Float = fromDegrees + (toDegrees - fromDegrees) * level / 10000f
    }

    /** A pack clock captured as layers, with its plain background when the drawable is adaptive. */
    class CapturedClock(val icon: ClockIcon, val background: Bitmap?)

    /**
     * The live-clock icon [sourcePack] declares for [app]'s component, with its layers rendered
     * out — or null when the pack declares none, isn't installed, the drawable isn't a layer stack,
     * or no verified rendering of it matches [bakedIcon].
     */
    fun clockIconFor(app: PackageInfoStruct, sourcePack: String, bakedIcon: IconPackDrawable): ClockIcon? {
        val elements = packElements.getOrPut(sourcePack) {
            indexedElements(sourcePack, allApps)
                ?: runCatching { appManager.getAppFilterRawElements(sourcePack, allApps) }.getOrDefault(emptyList())
        }
        val clocks = elements.filterIsInstance<RawDynamicClock>()
        if (clocks.isEmpty()) return null

        val component = app.toInstalledApplication().toComponentInfo()
        val drawableName = appFilterDrawableName(elements, component) ?: return null
        val meta = clocks.firstOrNull { it.drawableLink == drawableName } ?: return null
        val captured = captureClock(sourcePack, drawableName, meta) ?: return null

        val capturedBackground = captured.background
        if (capturedBackground == null) {
            val plain = captured.icon.authoredForeground()
            return captured.icon.takeIf { bakedIcon !is AdaptiveIconPackDrawable && plain.looksAlike(bakedIcon.toBitmap()) }
        }
        val baked = bakedIcon as? AdaptiveIconPackDrawable ?: return null
        // The export keeps the baked background, so only the rotating foreground has to match. Thin
        // hands are a few pixels at looksAlike's scale, where a recoloured clock still passes as the
        // plain one, so the closest candidate wins instead of the first acceptable one.
        val candidates = listOfNotNull(captured.icon.takeIf { capturedBackground.looksAlike(baked.background) }) +
            stageChains(app).mapNotNull { captured.icon.styledWith(context.resources, it) }
        val bakedForeground = baked.foreground
        return candidates
            .map { it to it.authoredForeground() }
            .filter { (_, foreground) -> foreground.looksAlike(bakedForeground) }
            .minByOrNull { (_, foreground) -> foreground.averageDifference(bakedForeground, DETAIL_MATCH_SIZE) }
            ?.first
    }

    /** [drawableName] from [sourcePack] split into its layers, or null when it is no usable clock. */
    fun captureClock(sourcePack: String, drawableName: String, meta: RawDynamicClock): CapturedClock? {
        val source = loadDrawable(sourcePack, drawableName) ?: return null
        val adaptive = if (PackageVersion.is26OrMore()) source as? AdaptiveIconDrawable else null
        val layered = (adaptive?.foreground ?: source) as? LayerDrawable ?: return null
        val activeHands = meta.handLayerIndices(layered.numberOfLayers)?.filter { it >= 0 } ?: return null

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
        val background = adaptive?.background?.let {
            it.setBounds(0, 0, size, size)
            renderAtCurrentBounds(it, size)
        }
        return CapturedClock(ClockIcon(layers, meta), background)
    }

    private fun loadDrawable(sourcePack: String, drawableName: String): Drawable? {
        val res = resourceResolver.getResources(sourcePack) ?: return null
        val id = res.getIdentifierByName(drawableName, "drawable", sourcePack)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null).mutate() }.getOrNull()
    }

    private fun renderAtCurrentBounds(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}

/**
 * Whether these modifiers act on an adaptive icon layer by layer, so applying them to each clock
 * layer separately equals applying them to the whole icon. Shape, outline and shadow flatten the
 * icon, and every image edit other than plain Colorize works on the flattened frame.
 */
internal fun GenerationOptions.keepsClockLayers(): Boolean {
    if (export.themed || modifiers.requiresRasterization) return false
    return when (modifiers.effect.imageEdit) {
        ImageEdit.NONE -> true
        ImageEdit.COLORIZE -> modifiers.effect.segments.isEmpty()
        else -> false
    }
}

/**
 * Applies [chain] (a local stage, optionally followed by the Global style) to every layer on its
 * own, moving each hand's pivot with the layer transform. Null when a stage would flatten.
 */
internal fun DynamicClockExporter.ClockIcon.styledWith(
    resources: Resources,
    chain: List<GenerationOptions>
): DynamicClockExporter.ClockIcon? {
    if (chain.isEmpty() || chain.any { !it.keepsClockLayers() }) return null
    val size = layers.first().bitmap.width
    val clear = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val styled = layers.map { layer ->
        var icon = AdaptiveIconPackDrawable.ofLayers(layer.bitmap, clear)
        var rotation = layer.rotation
        for (options in chain) {
            icon = IconImageEditPipeline(resources, options).applyPrimary(icon) as? AdaptiveIconPackDrawable ?: return null
            rotation = rotation?.movedBy(options.modifiers.transform)
        }
        DynamicClockExporter.ClockLayer(icon.foreground, rotation)
    }
    return DynamicClockExporter.ClockIcon(styled, meta)
}

// IconAdjustmentPipeline transforms adaptive layers by scaling about the centre after shifting by
// the offset divided by ADAPTIVE_ICON_SCALE; the pivot moves with its layer.
internal fun DynamicClockExporter.Rotation.movedBy(transform: TransformModifier): DynamicClockExporter.Rotation {
    if (transform.isIdentity) return this
    fun moved(relative: Float, offset: Float) = 0.5f + transform.scale * (relative + offset / ADAPTIVE_ICON_SCALE - 0.5f)
    return DynamicClockExporter.Rotation(
        fromDegrees, toDegrees, moved(pivotX, transform.offsetX), moved(pivotY, transform.offsetY)
    )
}
