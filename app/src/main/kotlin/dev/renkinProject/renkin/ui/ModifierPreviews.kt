package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode

/**
 * Everything the Modifier tab needs to preview a colour before it is applied, plus the artwork
 * its segment pickers cluster. Both hosts of the tab (the edit dialog and Global options' per-icon
 * editor) build these the same way, so the wiring lives here instead of twice.
 */
internal data class ModifierPreviews(
    /** The icon as the colourize step sees it: no scale, offset, shape or outline yet. */
    val colorizeBase: Bitmap?,
    val colorize: suspend (ColorizerStyle) -> Bitmap?,
    val outline: suspend (ColorizerStyle) -> Bitmap?,
    val layers: suspend (index: Int, draft: ColorizerStyle) -> Bitmap?
)

/**
 * Wires [render] — the host's own "generate with these options" call — into the previews the tab
 * expects. [options] must be the options the host is currently previewing with.
 */
@Composable
internal fun rememberModifierPreviews(
    options: GenerationOptions,
    adjustments: AdjustmentState,
    render: suspend (GenerationOptions) -> Bitmap?
): ModifierPreviews {
    val currentRender by rememberUpdatedState(render)
    val currentOptions by rememberUpdatedState(options)
    var colorizeBase by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(options) {
        colorizeBase = currentRender(
            options.copy(
                // Colourize runs BEFORE scale/offset/shape/outline, so the picker must cluster
                // the artwork without them — an outline or shape plate would otherwise offer a
                // colour that does not exist yet when the pick is applied.
                primaryImageEdit = ImageEdit.NONE,
                iconScale = 1f,
                iconOffsetX = 0f,
                iconOffsetY = 0f,
                iconShape = IconShape.NONE,
                outlineMode = OutlineMode.NONE,
                outlineEraseMask = null
            )
        )
    }

    return remember(colorizeBase) {
        ModifierPreviews(
            colorizeBase = colorizeBase,
            colorize = { style ->
                currentRender(currentOptions.withColorizerStyle(style))
            },
            outline = { style ->
                currentRender(
                    currentOptions.copy(
                        // The outline is only visible once it is actually being drawn.
                        outlineMode = adjustments.outlineMode.takeIf { it != OutlineMode.NONE }
                            ?: OutlineMode.ADD,
                        outlineColor = style.firstColor,
                        outlineStyle = style
                    )
                )
            },
            layers = { index, draft ->
                currentRender(
                    currentOptions.copy(
                        primaryImageEdit = ImageEdit.COLORIZE_SEGMENTS,
                        // The edited layer's draft stands in, so the preview shows it in the
                        // context of the layers around it.
                        colorizeLayers = adjustments.colorizeLayers.mapIndexed { i, layer ->
                            if (i == index) layer.copy(style = draft) else layer
                        }
                    )
                )
            }
        )
    }
}

/** The options with [style] substituted for the colourize settings. */
internal fun GenerationOptions.withColorizerStyle(style: ColorizerStyle): GenerationOptions = copy(
    primaryImageEdit = ImageEdit.COLORIZE,
    color = style.firstColor,
    colorizeFlat = style.flat,
    colorizeMonochrome = style.monochrome,
    colorizeInverse = style.inverse,
    colorizerMode = style.mode,
    colorizerGradientType = style.gradientType,
    colorizerGradientColors = style.gradientStops,
    colorizerGradientAngle = style.gradientAngle,
    colorizeLayers = emptyList()
)
