@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.ui.theme.CardShape

internal val POSITION_BLUEPRINT_DIALOG_MAX_WIDTH = 460.dp
internal val ERASE_BLUEPRINT_DIALOG_MAX_WIDTH = 460.dp

private val WideBlueprintMaxSize = 320.dp
private val WideBlueprintMinSize = 160.dp
private val SideControlWidth = 84.dp
private val SideControlSpacing = 8.dp

/**
 * Shared chrome for blueprint-style editors. The border is drawn by the same shape that clips
 * the content, so the grid and artwork meet one continuous rounded frame at every corner.
 */
internal fun Modifier.blueprintFrame(background: Color, frame: Color): Modifier =
    clip(CardShape)
        .background(background)
        .border(1.5.dp, frame, CardShape)

/** Draws the common eight-division grid beneath the rounded frame. */
internal fun DrawScope.drawBlueprintGrid(color: Color) {
    val thin = 1.dp.toPx()
    for (i in 1 until 8) {
        val p = size.width * i / 8f
        drawLine(color, Offset(p, 0f), Offset(p, size.height), thin)
        drawLine(color, Offset(0f, p), Offset(size.width, p), thin)
    }
}

/**
 * Compact wide-screen arrangement: an optional vertical control hugs the preview, while
 * additional controls stay directly below it. The whole group scrolls on short windows.
 */
@Composable
internal fun BlueprintSideControlLayout(
    canvas: @Composable (Modifier) -> Unit,
    sideControl: (@Composable (Modifier) -> Unit)? = null,
    bottomControl: (@Composable (Modifier) -> Unit)? = null,
    footerControl: (@Composable (Modifier) -> Unit)? = null,
    sideFooterControl: (@Composable (Modifier) -> Unit)? = null,
    bottomReservedHeight: Dp = 170.dp
) {
    val hasLowerControls =
        bottomControl != null || footerControl != null || sideFooterControl != null
    val reservedHeight = if (hasLowerControls) bottomReservedHeight else 120.dp
    val canvasSize = minOf(
        WideBlueprintMaxSize,
        (LocalConfiguration.current.screenHeightDp.dp - reservedHeight)
            .coerceAtLeast(WideBlueprintMinSize)
    )

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SideControlSpacing),
            verticalAlignment = Alignment.Top
        ) {
            canvas(Modifier.size(canvasSize))
            sideControl?.invoke(Modifier.width(SideControlWidth).height(canvasSize))
        }
        if (hasLowerControls) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(SideControlSpacing),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier.width(canvasSize),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    bottomControl?.invoke(Modifier.fillMaxWidth())
                    if (bottomControl != null && footerControl != null) {
                        Spacer(Modifier.height(8.dp))
                    }
                    footerControl?.invoke(Modifier.fillMaxWidth())
                }
                if (sideControl != null || sideFooterControl != null) {
                    if (sideFooterControl != null) {
                        sideFooterControl(Modifier.width(SideControlWidth))
                    } else {
                        Spacer(Modifier.width(SideControlWidth))
                    }
                }
            }
        }
    }
}

/** Compact-screen fallback: stack the preview and controls, with scrolling for short windows. */
@Composable
internal fun BlueprintStackedLayout(
    canvas: @Composable (Modifier) -> Unit,
    controls: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        canvas(Modifier.fillMaxWidth().aspectRatio(1f))
        controls()
    }
}

/** Native Material 3 vertical slider with a compact, upright label above it. */
@Composable
internal fun VerticalLabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    reverseValue: Boolean = false
) {
    val displayValue = if (reverseValue) {
        valueRange.start + valueRange.endInclusive - value
    } else {
        value
    }
    val sliderState = rememberSliderState(
        value = displayValue,
        valueRange = valueRange
    )
    SideEffect {
        if (!sliderState.isDragging) sliderState.value = displayValue
        sliderState.onValueChange = { changed ->
            // A custom callback takes ownership of state updates; keep the thumb under the
            // finger during a drag, then let the external value drive settled/tapped changes.
            if (sliderState.isDragging) sliderState.value = changed
            onValueChange(
                if (reverseValue) {
                    valueRange.start + valueRange.endInclusive - changed
                } else {
                    changed
                }
            )
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
        if (centered) {
            VerticalSlider(
                state = sliderState,
                modifier = Modifier.weight(1f),
                reverseDirection = true,
                track = { SliderDefaults.CenteredTrack(sliderState = it) }
            )
        } else {
            VerticalSlider(
                state = sliderState,
                modifier = Modifier.weight(1f),
                reverseDirection = true
            )
        }
    }
}
