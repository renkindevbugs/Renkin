package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import kotlin.math.roundToInt

/** Saved-state value read back as a style, tolerating the plain ARGB int older builds wrote. */
private fun Any?.toColorizerStyle(fallback: ColorizerStyle): ColorizerStyle = when (this) {
    is String -> decodeColorizerStyle(this)
    is Int -> ColorizerStyle(firstColor = this)
    else -> null
} ?: fallback

@Stable
internal class MaterialYouPackAdjustmentState(initial: MaterialYouPackEditState? = null) {
    var selectedScheme by mutableIntStateOf(initial?.selectedScheme ?: -1)
    // Full styles, not plain colours: a pack's Material You icon keeps only the first stop (its
    // layers are paths), but the generated variant can carry the whole gradient.
    var customForeground by mutableStateOf(
        initial?.customForeground ?: MaterialYouPackEditState.DEFAULT_FOREGROUND
    )
    var customBackground by mutableStateOf(
        initial?.customBackground ?: MaterialYouPackEditState.DEFAULT_BACKGROUND
    )
    var strokeScale by mutableFloatStateOf(initial?.strokeScale ?: 1f)

    fun reset() {
        selectedScheme = -1
        customForeground = MaterialYouPackEditState.DEFAULT_FOREGROUND
        customBackground = MaterialYouPackEditState.DEFAULT_BACKGROUND
        strokeScale = 1f
    }

    companion object {
        val Saver = Saver<MaterialYouPackAdjustmentState, Any>(
            save = {
                arrayListOf(
                    it.selectedScheme,
                    encodeColorizerStyle(it.customForeground),
                    encodeColorizerStyle(it.customBackground),
                    it.strokeScale
                )
            },
            restore = { saved ->
                val values = saved as? List<*>
                if (values == null) {
                    MaterialYouPackAdjustmentState()
                } else {
                    MaterialYouPackAdjustmentState().apply {
                        selectedScheme = values.getOrNull(0) as? Int ?: -1
                        // Older saved states stored plain ARGB ints; both shapes restore.
                        customForeground = values.getOrNull(1).toColorizerStyle(
                            MaterialYouPackEditState.DEFAULT_FOREGROUND
                        )
                        customBackground = values.getOrNull(2).toColorizerStyle(
                            MaterialYouPackEditState.DEFAULT_BACKGROUND
                        )
                        strokeScale = values.getOrNull(3) as? Float ?: 1f
                    }
                }
            }
        )
    }
}

internal fun lineWeightToCenteredSlider(scale: Float): Float =
    if (scale <= 1f) ((scale.coerceIn(0.5f, 1f) - 0.5f) * 2f)
    else scale.coerceIn(1f, 2f)

internal fun centeredSliderToLineWeight(value: Float): Float {
    val scale = if (value <= 1f) 0.5f + value.coerceIn(0f, 1f) * 0.5f
    else value.coerceIn(1f, 2f)
    // The label rounds to whole percent. Make every displayed 100% the exact identity value,
    // otherwise returning the thumb visually to centre could keep a tiny adjustment active.
    return if ((scale * 100).roundToInt() == 100) 1f else scale
}
