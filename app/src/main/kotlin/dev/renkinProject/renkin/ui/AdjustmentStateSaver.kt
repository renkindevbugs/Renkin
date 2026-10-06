package dev.renkinProject.renkin.ui

import androidx.compose.runtime.saveable.Saver
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.decodeSegmentLayer

internal object AdjustmentStateSaver {
    // Keep a keyed envelope for UI-only state and accept both older keyed and positional lists.
    // mapSaver cannot read the positional form because its first item is a Float, not a key.
    val saver = Saver<AdjustmentState, Any>(
        save = {
            arrayListOf(
                "modifiers", ModifierRecipeCodec.encode(it.savedModifierStack()),
                "autoCenter", it.autoCenter
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

    private fun restoreKeyed(saved: Map<String, Any?>): AdjustmentState? {
        (saved["modifiers"] as? String)?.let { encoded ->
            val stack = ModifierRecipeCodec.decodeSavedState(encoded) ?: return null
            return AdjustmentState().apply {
                restoreSavedModifiers(stack)
                autoCenter = saved["autoCenter"] as? Boolean ?: false
            }
        }
        return restoreLegacyKeyed(saved)
    }

    private fun restoreLegacyKeyed(saved: Map<String, Any?>) = AdjustmentState().apply {
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
        shapeStyle = restoreStyle(saved, "shapeStyle", "shape", "shapeColor", shapeStyle)
        shapeScale = saved["shapeScale"] as? Float ?: shapeScale
        outlineMode = OutlineMode.entries.getOrElse(saved["outlineMode"] as? Int ?: 0) { OutlineMode.NONE }
        outlineWidth = saved["outlineWidth"] as? Float ?: outlineWidth
        outlineStyle = restoreStyle(saved, "outlineStyle", "outline", "outlineColor", outlineStyle)
        shadowEnabled = saved["shadowEnabled"] as? Boolean ?: shadowEnabled
        shadowBlur = saved["shadowBlur"] as? Float ?: shadowBlur
        shadowDistance = saved["shadowDistance"] as? Float ?: shadowDistance
        shadowAngle = saved["shadowAngle"] as? Float ?: shadowAngle
        shadowAllDirections =
            saved["shadowAllDirections"] as? Boolean ?: shadowAllDirections
        shadowStyle = restoreStyle(saved, "shadowStyle", "shadow", "shadowColor", shadowStyle)
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
        colorizerStyle = colorizerStyle.copy(
            flat = saved.getOrNull(8) as? Boolean ?: colorizerStyle.flat,
            monochrome = saved.getOrNull(16) as? Boolean ?: colorizerStyle.monochrome,
            inverse = saved.getOrNull(17) as? Boolean ?: colorizerStyle.inverse
        )
        iconShape = IconShape.entries.getOrElse(saved.getOrNull(9) as? Int ?: 0) { IconShape.NONE }
        shapeCrop = saved.getOrNull(10) as? Boolean ?: shapeCrop
        (saved.getOrNull(11) as? Int)?.let { shapeStyle = shapeStyle.copy(firstColor = it) }
        shapeScale = saved.getOrNull(12) as? Float ?: shapeScale
        outlineMode = OutlineMode.entries.getOrElse(saved.getOrNull(13) as? Int ?: 0) { OutlineMode.NONE }
        outlineWidth = saved.getOrNull(14) as? Float ?: outlineWidth
        (saved.getOrNull(15) as? Int)?.let { outlineStyle = outlineStyle.copy(firstColor = it) }
    }
}
