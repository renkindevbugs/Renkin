package dev.renkinProject.renkin.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import dev.renkinProject.renkin.data.FALLBACK_SOURCE_DEFAULT
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_DEFAULT
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.OutlineModifier
import dev.renkinProject.renkin.icon.creator.ShapeModifier
import dev.renkinProject.renkin.icon.creator.TransformModifier
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle

internal val GlobalModifierStateSaver = Saver<GlobalModifierState, Any>(
    save = { it.snapshot().toSaveableList() },
    restore = { saved -> restoreGlobalSnapshot(saved)?.let { snapshot ->
        GlobalModifierState().apply { restore(snapshot) }
    } }
)

internal val GlobalModifierBaselineSaver = Saver<MutableState<GlobalModifierSnapshot?>, Any>(
    save = { it.value?.toSaveableList() },
    restore = { saved -> mutableStateOf(restoreGlobalSnapshot(saved)) }
)

/** A history entry staged in the controls: when it was applied and the draft it replaced. */
internal data class GlobalHistoryLoad(val appliedAt: Long, val before: GlobalModifierSnapshot) {
    companion object {
        // appliedAt of the always-present "No global style" row, which has no Apply time.
        const val NO_GLOBAL_STYLE = 0L
    }
}

internal val GlobalHistoryLoadSaver = Saver<MutableState<GlobalHistoryLoad?>, Any>(
    save = { state -> state.value?.let { arrayListOf(it.appliedAt, it.before.toSaveableList()) } },
    restore = { saved ->
        val values = saved as? List<*>
        val appliedAt = values?.getOrNull(0) as? Long
        val before = values?.getOrNull(1)?.let(::restoreGlobalSnapshot)
        mutableStateOf(if (appliedAt != null && before != null) GlobalHistoryLoad(appliedAt, before) else null)
    }
)

private fun GlobalModifierSnapshot.toSaveableList(): ArrayList<Any> = arrayListOf(
    "primarySource", primarySource.ordinal,
    "primaryIconPack", primaryIconPack,
    "secondarySource", secondarySource.ordinal,
    "secondaryIconPack", secondaryIconPack,
    "fallbackSource", fallbackSource.ordinal,
    "modifiers", ModifierRecipeCodec.encode(modifiers),
    "applyGenerated", applyGenerated,
    "applyExisting", applyExisting,
    "applyCustom", applyCustom,
    "includeEmpty", includeEmpty
)

private fun restoreGlobalSnapshot(saved: Any): GlobalModifierSnapshot? = (when (saved) {
    is List<*> -> saved.toGlobalModifierSnapshot()
    is String -> restoreLegacyGlobalSnapshot(saved)
    else -> null
})?.let { it.copy(modifiers = it.modifiers.withGlobalDraftDefaults().withNormalizedGlobalColorizer()) }

private fun List<*>.toGlobalModifierSnapshot(): GlobalModifierSnapshot? {
    if (firstOrNull() !is String) return null
    val values = buildMap<String, Any?> {
        var index = 0
        while (index + 1 < this@toGlobalModifierSnapshot.size) {
            val key = this@toGlobalModifierSnapshot[index] as? String ?: break
            put(key, this@toGlobalModifierSnapshot[index + 1])
            index += 2
        }
    }
    val modifiers = if ("modifiers" in values) {
        val recipe = values["modifiers"] as? String ?: return null
        ModifierRecipeCodec.decodeSavedState(recipe)?.withGlobalDraftDefaults() ?: return null
    } else {
        val shapeStyle = (values["shapeStyle"] as? String)?.let(::decodeColorizerStyle) ?: return null
        val outlineStyle = (values["outlineStyle"] as? String)?.let(::decodeColorizerStyle) ?: return null
        val colorizerStyle = (values["colorizerStyle"] as? String)?.let(::decodeColorizerStyle) ?: return null
        ModifierStack(
            effect = EffectModifier(
                if (values["colorize"] as? Boolean == true) ImageEdit.COLORIZE else ImageEdit.NONE,
                colorizerStyle
            ),
            transform = TransformModifier((values["iconScalePercent"] as? Int ?: 100) / 100f),
            shape = ShapeModifier(
                IconShape.entries.getOrElse(values["shape"] as? Int ?: 0) { IconShape.NONE },
                values["shapeCrop"] as? Boolean ?: true,
                (values["shapeScalePercent"] as? Int ?: 100) / 100f,
                shapeStyle.asFill()
            ),
            outline = OutlineModifier(
                if (values["outlineAdd"] as? Boolean == true) OutlineMode.ADD else OutlineMode.NONE,
                (values["outlineWidth"] as? Int ?: OUTLINE_WIDTH_DEFAULT).toFloat(),
                outlineStyle.asFill()
            )
        )
    }
    return GlobalModifierSnapshot(
        primarySource = Source.entries.getOrElse(values["primarySource"] as? Int ?: 0) { SOURCE_DEFAULT },
        primaryIconPack = values["primaryIconPack"] as? String ?: "",
        secondarySource = Source.entries.getOrElse(values["secondarySource"] as? Int ?: 0) { SOURCE_DEFAULT },
        secondaryIconPack = values["secondaryIconPack"] as? String ?: "",
        fallbackSource = FallbackSource.entries.getOrElse(values["fallbackSource"] as? Int ?: 0) { FALLBACK_SOURCE_DEFAULT },
        modifiers = modifiers,
        applyGenerated = values["applyGenerated"] as? Boolean ?: true,
        applyExisting = values["applyExisting"] as? Boolean ?: false,
        applyCustom = values["applyCustom"] as? Boolean ?: false,
        includeEmpty = values["includeEmpty"] as? Boolean ?: false
    )
}

private fun restoreLegacyGlobalSnapshot(saved: String): GlobalModifierSnapshot? {
    val values = saved.split('|')
    if (values.size !in intArrayOf(17, 20, 21, 25, 27, 32)) return null
    return runCatching {
        val shape = IconShape.entries[values[0].toInt()]
        val shapeCrop = values[1].toBooleanStrict()
        val shapeScale = values[2].toInt() / 100f
        var shapeStyle = ColorizerStyle(firstColor = values[3].toInt())
        val iconScale = values[4].toInt() / 100f
        val outlineAdd = values[5].toBooleanStrict()
        val outlineWidth = values[6].toInt().toFloat()
        var outlineStyle = ColorizerStyle(firstColor = values[7].toInt())
        val colorize = values[8].toBooleanStrict()
        var colorizerStyle = ColorizerStyle(
            firstColor = values[9].toInt(),
            flat = values[10].toBooleanStrict(),
            monochrome = values[15].toBooleanStrict(),
            inverse = values[16].toBooleanStrict()
        )
        val applyGenerated = values[11].toBooleanStrict()
        val applyExisting = values[12].toBooleanStrict()
        val applyCustom = values[13].toBooleanStrict()
        val includeEmpty = values[14].toBooleanStrict()
        if (values.size >= 20) {
            colorizerStyle = colorizerStyle.copy(
                mode = ColorizerMode.entries.getOrElse(values[17].toInt()) {
                    ColorizerMode.SINGLE_COLOR
                },
                gradientStops = values[18].split(',').mapNotNull { it.toIntOrNull() }
                    .ifEmpty { listOf(android.graphics.Color.BLACK) },
                gradientAngle = values[19].toInt().coerceIn(0, 360).toFloat()
            )
        }
        if (values.size >= 21) {
            colorizerStyle = colorizerStyle.copy(
                gradientType = GradientType.entries.getOrElse(values[20].toInt()) {
                    GradientType.LINEAR
                }
            )
        }
        if (values.size >= 25) {
            outlineStyle = outlineStyle.copy(
                mode = ColorizerMode.entries.getOrElse(values[21].toInt()) {
                    ColorizerMode.SINGLE_COLOR
                },
                gradientType = GradientType.entries.getOrElse(values[22].toInt()) {
                    GradientType.LINEAR
                },
                gradientStops = values[23].split(',').mapNotNull { it.toIntOrNull() }
                    .ifEmpty { listOf(android.graphics.Color.BLACK) },
                gradientAngle = values[24].toInt().coerceIn(0, 360).toFloat()
            )
        }
        if (values.size >= 27) {
            colorizerStyle = colorizerStyle.copy(
                gradientPositions = values[25].split(',').mapNotNull { it.toFloatOrNull() }
            )
            outlineStyle = outlineStyle.copy(
                gradientPositions = values[26].split(',').mapNotNull { it.toFloatOrNull() }
            )
        }
        if (values.size >= 32) {
            shapeStyle = shapeStyle.copy(
                mode = ColorizerMode.entries.getOrElse(values[27].toInt()) {
                    ColorizerMode.SINGLE_COLOR
                },
                gradientType = GradientType.entries.getOrElse(values[28].toInt()) {
                    GradientType.LINEAR
                },
                gradientStops = values[29].split(',').mapNotNull { it.toIntOrNull() }
                    .ifEmpty { listOf(android.graphics.Color.BLACK) },
                gradientPositions = values[30].split(',').mapNotNull { it.toFloatOrNull() },
                gradientAngle = values[31].toInt().coerceIn(0, 360).toFloat()
            )
        }
        GlobalModifierSnapshot(
            primarySource = SOURCE_DEFAULT,
            primaryIconPack = "",
            secondarySource = SOURCE_DEFAULT,
            secondaryIconPack = "",
            fallbackSource = FALLBACK_SOURCE_DEFAULT,
            modifiers = ModifierStack(
                effect = EffectModifier(if (colorize) ImageEdit.COLORIZE else ImageEdit.NONE, colorizerStyle),
                transform = TransformModifier(iconScale),
                shape = ShapeModifier(shape, shapeCrop, shapeScale, shapeStyle.asFill()),
                outline = OutlineModifier(if (outlineAdd) OutlineMode.ADD else OutlineMode.NONE, outlineWidth, outlineStyle.asFill())
            ),
            applyGenerated = applyGenerated,
            applyExisting = applyExisting,
            applyCustom = applyCustom,
            includeEmpty = includeEmpty
        )
    }.getOrNull()
}
