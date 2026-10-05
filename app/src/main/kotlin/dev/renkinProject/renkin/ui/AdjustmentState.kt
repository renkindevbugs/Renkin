package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.decodeSegmentLayer
import dev.renkinProject.renkin.icon.creator.encode
import dev.renkinProject.renkin.icon.creator.SHADOW_ANGLE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_DEFAULT

/**
 * The Modifier tab's per-icon adjustment state (edge tuning, background-removal tolerance, scale,
 * position), shared by both editors. Each colour group owns one immutable style.
 * Plain Compose state; [Saver] keeps it across process death.
 */
@Stable
internal class AdjustmentState {
    // The first Colorize stop is supplied by the dialog's shared iconColor at render time.
    var colorizerStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.WHITE))
    var shapeColorStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.WHITE))
    var outlineColorStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.BLACK))
    var shadowColorStyle by mutableStateOf(ColorizerStyle(firstColor = android.graphics.Color.BLACK))

    var edgeThreshold by mutableFloatStateOf(2.5f)
    var edgeSmoothing by mutableFloatStateOf(2f)
    var edgeContrast by mutableStateOf(false)
    // These control accessors update the same style; they own no independent state.
    // Colorize as a flat fill (SRC_IN) instead of the default multiply blend, so the picked
    // colour lands exactly — a green icon tinted blue no longer muddies to a green/blue mix.
    var colorizeFlat: Boolean
        get() = colorizerStyle.flat
        set(value) { colorizerStyle = colorizerStyle.copy(flat = value) }
    var colorizeLighten: Boolean
        get() = colorizerStyle.lighten
        set(value) { colorizerStyle = colorizerStyle.copy(lighten = value) }
    var colorizeMonochrome: Boolean
        get() = colorizerStyle.monochrome
        set(value) { colorizerStyle = colorizerStyle.copy(monochrome = value) }
    var colorizeInverse: Boolean
        get() = colorizerStyle.inverse
        set(value) { colorizerStyle = colorizerStyle.copy(inverse = value) }
    var colorizerMode: ColorizerMode
        get() = colorizerStyle.mode
        set(value) { colorizerStyle = colorizerStyle.copy(mode = value) }
    var colorizerGradientType: GradientType
        get() = colorizerStyle.gradientType
        set(value) { colorizerStyle = colorizerStyle.copy(gradientType = value) }
    var colorizerGradientColors: List<Int>
        get() = colorizerStyle.gradientStops
        set(value) { colorizerStyle = colorizerStyle.copy(gradientStops = value) }
    var colorizerGradientPositions: List<Float>
        get() = colorizerStyle.gradientPositions
        set(value) { colorizerStyle = colorizerStyle.copy(gradientPositions = value) }
    var colorizerGradientAngle: Float
        get() = colorizerStyle.gradientAngle
        set(value) { colorizerStyle = colorizerStyle.copy(gradientAngle = value) }
    // Per-region colourize steps of the Colorize segments modifier, applied in order.
    var colorizeLayers by mutableStateOf(emptyList<SegmentLayer>())
    var iconScale by mutableFloatStateOf(1f)
    // Colours the user picked to drop; empty leaves the automatic border flood in charge.
    var bgRemovalTargets by mutableStateOf(emptyList<Int>())
    var bgRemovalTolerance by mutableFloatStateOf(0.1f)
    // Auto-center is UI state only: switching it on computes the offsets below (the pipeline's
    // single source of truth for position); dragging a position slider switches it back off.
    var autoCenter by mutableStateOf(false)
    var iconOffsetX by mutableFloatStateOf(0f)
    var iconOffsetY by mutableFloatStateOf(0f)
    // Icon shape (applied as the last generation step): the shape, crop-vs-plate mode
    // (crop is the default — most icons are full-bleed), the icon's size relative to
    // the shape, and the plate's fill colour.
    var iconShape by mutableStateOf(IconShape.NONE)
    var shapeCrop by mutableStateOf(true)
    var shapeScale by mutableFloatStateOf(1f)
    var shapeColor: Color
        get() = Color(shapeColorStyle.firstColor)
        set(value) { shapeColorStyle = shapeColorStyle.copy(firstColor = value.toArgb()) }
    // The plate carries its own colour style, so it can be a gradient like Colorize.
    var shapeColorizerMode: ColorizerMode
        get() = shapeColorStyle.mode
        set(value) { shapeColorStyle = shapeColorStyle.copy(mode = value) }
    var shapeGradientType: GradientType
        get() = shapeColorStyle.gradientType
        set(value) { shapeColorStyle = shapeColorStyle.copy(gradientType = value) }
    var shapeGradientColors: List<Int>
        get() = shapeColorStyle.gradientStops
        set(value) { shapeColorStyle = shapeColorStyle.copy(gradientStops = value) }
    var shapeGradientPositions: List<Float>
        get() = shapeColorStyle.gradientPositions
        set(value) { shapeColorStyle = shapeColorStyle.copy(gradientPositions = value) }
    var shapeGradientAngle: Float
        get() = shapeColorStyle.gradientAngle
        set(value) { shapeColorStyle = shapeColorStyle.copy(gradientAngle = value) }
    // Outline: add a contour around the silhouette, or recolor the icon's existing one.
    var outlineMode by mutableStateOf(OutlineMode.NONE)
    var outlineWidth by mutableFloatStateOf(6f)
    var outlineColor: Color
        get() = Color(outlineColorStyle.firstColor)
        set(value) { outlineColorStyle = outlineColorStyle.copy(firstColor = value.toArgb()) }
    // The outline carries its own colour style, so it can be a gradient like Colorize.
    var outlineColorizerMode: ColorizerMode
        get() = outlineColorStyle.mode
        set(value) { outlineColorStyle = outlineColorStyle.copy(mode = value) }
    var outlineGradientType: GradientType
        get() = outlineColorStyle.gradientType
        set(value) { outlineColorStyle = outlineColorStyle.copy(gradientType = value) }
    var outlineGradientColors: List<Int>
        get() = outlineColorStyle.gradientStops
        set(value) { outlineColorStyle = outlineColorStyle.copy(gradientStops = value) }
    var outlineGradientPositions: List<Float>
        get() = outlineColorStyle.gradientPositions
        set(value) { outlineColorStyle = outlineColorStyle.copy(gradientPositions = value) }
    var outlineGradientAngle: Float
        get() = outlineColorStyle.gradientAngle
        set(value) { outlineColorStyle = outlineColorStyle.copy(gradientAngle = value) }
    var shadowEnabled by mutableStateOf(false)
    var shadowBlur by mutableFloatStateOf(SHADOW_BLUR_DEFAULT)
    var shadowDistance by mutableFloatStateOf(SHADOW_DISTANCE_DEFAULT)
    var shadowAngle by mutableFloatStateOf(SHADOW_ANGLE_DEFAULT)
    var shadowAllDirections by mutableStateOf(false)
    var shadowColor: Color
        get() = Color(shadowColorStyle.firstColor)
        set(value) { shadowColorStyle = shadowColorStyle.copy(firstColor = value.toArgb()) }
    var shadowColorizerMode: ColorizerMode
        get() = shadowColorStyle.mode
        set(value) { shadowColorStyle = shadowColorStyle.copy(mode = value) }
    var shadowGradientType: GradientType
        get() = shadowColorStyle.gradientType
        set(value) { shadowColorStyle = shadowColorStyle.copy(gradientType = value) }
    var shadowGradientColors: List<Int>
        get() = shadowColorStyle.gradientStops
        set(value) { shadowColorStyle = shadowColorStyle.copy(gradientStops = value) }
    var shadowGradientPositions: List<Float>
        get() = shadowColorStyle.gradientPositions
        set(value) { shadowColorStyle = shadowColorStyle.copy(gradientPositions = value) }
    var shadowGradientAngle: Float
        get() = shadowColorStyle.gradientAngle
        set(value) { shadowColorStyle = shadowColorStyle.copy(gradientAngle = value) }
    var shadowOpacity by mutableFloatStateOf(SHADOW_OPACITY_DEFAULT)
    // Eraser strokes masking where the outline must not apply. Deliberately NOT in [Saver]:
    // they're transient per-app geometry, and holding them out keeps the saver list flat.
    var eraseStrokes by mutableStateOf<List<BrushStroke>>(emptyList())
    // Hand corrections to the background removal, same reasoning: transient per-app geometry.
    var backgroundBrushStrokes by mutableStateOf<List<BrushStroke>>(emptyList())

    companion object {
        // Keep the keyed representation, but accept the positional list emitted by older builds.
        // mapSaver itself cannot do that because it casts every even list item to String before
        // calling restore, which would crash on the legacy list's first Float value.
        val Saver = Saver<AdjustmentState, Any>(
            save = {
                arrayListOf(
                    "edgeThreshold", it.edgeThreshold,
                    "edgeSmoothing", it.edgeSmoothing,
                    "edgeContrast", it.edgeContrast,
                    "iconScale", it.iconScale,
                    "bgRemovalTolerance", it.bgRemovalTolerance,
                    "bgRemovalTargets", it.bgRemovalTargets,
                    "autoCenter", it.autoCenter,
                    "iconOffsetX", it.iconOffsetX,
                    "iconOffsetY", it.iconOffsetY,
                    "colorizerStyle", encodeColorizerStyle(it.colorizerStyle),
                    "colorizeLayers", it.colorizeLayers.map(SegmentLayer::encode),
                    "iconShape", it.iconShape.ordinal,
                    "shapeCrop", it.shapeCrop,
                    "shapeStyle", encodeColorizerStyle(it.shapeColorStyle),
                    "shapeScale", it.shapeScale,
                    "outlineMode", it.outlineMode.ordinal,
                    "outlineWidth", it.outlineWidth,
                    "outlineStyle", encodeColorizerStyle(it.outlineColorStyle),
                    "shadowEnabled", it.shadowEnabled,
                    "shadowBlur", it.shadowBlur,
                    "shadowDistance", it.shadowDistance,
                    "shadowAngle", it.shadowAngle,
                    "shadowAllDirections", it.shadowAllDirections,
                    "shadowStyle", encodeColorizerStyle(it.shadowColorStyle),
                    "shadowOpacity", it.shadowOpacity
                )
            },
            restore = ::restoreAdjustmentState
        )

        private fun restoreAdjustmentState(saved: Any): AdjustmentState? {
            val values = saved as? List<*> ?: return null
            return if (values.firstOrNull() is String) {
                val keyed = buildMap<String, Any?> {
                    var index = 0
                    while (index + 1 < values.size) {
                        val key = values[index] as? String ?: break
                        put(key, values[index + 1])
                        index += 2
                    }
                }
                restoreKeyed(keyed)
            } else {
                restoreLegacy(values)
            }
        }

        private fun restoreKeyed(saved: Map<String, Any?>) = AdjustmentState().apply {
            edgeThreshold = saved["edgeThreshold"] as? Float ?: edgeThreshold
            edgeSmoothing = saved["edgeSmoothing"] as? Float ?: edgeSmoothing
            edgeContrast = saved["edgeContrast"] as? Boolean ?: edgeContrast
            iconScale = saved["iconScale"] as? Float ?: iconScale
            bgRemovalTolerance = saved["bgRemovalTolerance"] as? Float ?: bgRemovalTolerance
            (saved["bgRemovalTargets"] as? List<*>)?.filterIsInstance<Int>()?.let {
                bgRemovalTargets = it
            }
            autoCenter = saved["autoCenter"] as? Boolean ?: autoCenter
            iconOffsetX = saved["iconOffsetX"] as? Float ?: iconOffsetX
            iconOffsetY = saved["iconOffsetY"] as? Float ?: iconOffsetY
            colorizerStyle = restoreStyle(saved, "colorizerStyle", "colorizer", null, colorizerStyle)
            (saved["colorizeLayers"] as? List<*>)?.let { encoded ->
                colorizeLayers = encoded.filterIsInstance<String>()
                    .mapNotNull(::decodeSegmentLayer)
            }
            iconShape = IconShape.entries.getOrElse(saved["iconShape"] as? Int ?: 0) { IconShape.NONE }
            shapeCrop = saved["shapeCrop"] as? Boolean ?: shapeCrop
            shapeColorStyle = restoreStyle(saved, "shapeStyle", "shape", "shapeColor", shapeColorStyle)
            shapeScale = saved["shapeScale"] as? Float ?: shapeScale
            outlineMode = OutlineMode.entries.getOrElse(saved["outlineMode"] as? Int ?: 0) { OutlineMode.NONE }
            outlineWidth = saved["outlineWidth"] as? Float ?: outlineWidth
            outlineColorStyle = restoreStyle(saved, "outlineStyle", "outline", "outlineColor", outlineColorStyle)
            shadowEnabled = saved["shadowEnabled"] as? Boolean ?: shadowEnabled
            shadowBlur = saved["shadowBlur"] as? Float ?: shadowBlur
            shadowDistance = saved["shadowDistance"] as? Float ?: shadowDistance
            shadowAngle = saved["shadowAngle"] as? Float ?: shadowAngle
            shadowAllDirections =
                saved["shadowAllDirections"] as? Boolean ?: shadowAllDirections
            shadowColorStyle = restoreStyle(saved, "shadowStyle", "shadow", "shadowColor", shadowColorStyle)
            shadowOpacity = saved["shadowOpacity"] as? Float ?: shadowOpacity
        }

        private fun restoreStyle(
            saved: Map<String, Any?>,
            key: String,
            prefix: String,
            colorKey: String?,
            fallback: ColorizerStyle
        ): ColorizerStyle {
            (saved[key] as? String)?.let { decodeColorizerStyle(it)?.let { style -> return style } }
            val isColorize = prefix == "colorizer"
            val modeKey = if (isColorize) "colorizerMode" else "${prefix}ColorizerMode"
            val stops = (saved["${prefix}GradientColors"] as? List<*>)?.filterIsInstance<Int>()
                ?.takeIf { it.isNotEmpty() }
                ?: if (isColorize) (saved["colorizerGradientColor"] as? Int)?.let { listOf(it) } else null
            return fallback.copy(
                mode = ColorizerMode.entries.getOrElse(saved[modeKey] as? Int ?: fallback.mode.ordinal) { fallback.mode },
                gradientType = GradientType.entries.getOrElse(
                    saved["${prefix}GradientType"] as? Int ?: fallback.gradientType.ordinal
                ) { fallback.gradientType },
                firstColor = colorKey?.let { saved[it] as? Int } ?: fallback.firstColor,
                gradientStops = stops ?: fallback.gradientStops,
                gradientPositions = (saved["${prefix}GradientPositions"] as? List<*>)
                    ?.filterIsInstance<Float>() ?: fallback.gradientPositions,
                gradientAngle = saved["${prefix}GradientAngle"] as? Float ?: fallback.gradientAngle,
                flat = if (isColorize) saved["colorizeFlat"] as? Boolean ?: fallback.flat else fallback.flat,
                lighten = if (isColorize) saved["colorizeLighten"] as? Boolean ?: fallback.lighten else fallback.lighten,
                monochrome = if (isColorize) saved["colorizeMonochrome"] as? Boolean ?: fallback.monochrome else fallback.monochrome,
                inverse = if (isColorize) saved["colorizeInverse"] as? Boolean ?: fallback.inverse else fallback.inverse
            )
        }

        private fun restoreLegacy(saved: List<*>) = AdjustmentState().apply {
            edgeThreshold = saved.getOrNull(0) as? Float ?: edgeThreshold
            edgeSmoothing = saved.getOrNull(1) as? Float ?: edgeSmoothing
            edgeContrast = saved.getOrNull(2) as? Boolean ?: edgeContrast
            iconScale = saved.getOrNull(3) as? Float ?: iconScale
            bgRemovalTolerance = saved.getOrNull(4) as? Float ?: bgRemovalTolerance
            autoCenter = saved.getOrNull(5) as? Boolean ?: autoCenter
            iconOffsetX = saved.getOrNull(6) as? Float ?: iconOffsetX
            iconOffsetY = saved.getOrNull(7) as? Float ?: iconOffsetY
            colorizeFlat = saved.getOrNull(8) as? Boolean ?: colorizeFlat
            iconShape = IconShape.entries.getOrElse(saved.getOrNull(9) as? Int ?: 0) { IconShape.NONE }
            shapeCrop = saved.getOrNull(10) as? Boolean ?: shapeCrop
            (saved.getOrNull(11) as? Int)?.let { shapeColor = Color(it) }
            shapeScale = saved.getOrNull(12) as? Float ?: shapeScale
            outlineMode = OutlineMode.entries.getOrElse(saved.getOrNull(13) as? Int ?: 0) { OutlineMode.NONE }
            outlineWidth = saved.getOrNull(14) as? Float ?: outlineWidth
            (saved.getOrNull(15) as? Int)?.let { outlineColor = Color(it) }
            colorizeMonochrome = saved.getOrNull(16) as? Boolean ?: colorizeMonochrome
            colorizeInverse = saved.getOrNull(17) as? Boolean ?: colorizeInverse
        }
    }
}
