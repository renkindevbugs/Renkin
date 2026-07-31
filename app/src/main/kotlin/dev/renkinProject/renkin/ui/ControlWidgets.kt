@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.CardShape

// Small controls shared across the Modifier tab and the Options screens, so each doesn't carry
// its own copy of the same segmented selector / option card / labeled slider.

/** Rounded, bordered container for a row of [SegmentCell]s. */
@Composable
fun SegmentedRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(InnerShape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, InnerShape),
        content = content
    )
}

/**
 * One cell of a segmented selector (icon variant, fallback source, …). A disabled cell still
 * catches the tap and shows [disabledHint], so the dead segment explains what to do instead of
 * silently ignoring the touch.
 */
@Composable
fun SegmentCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledHint: String? = null,
    badge: @Composable BoxScope.() -> Unit = {},
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val toaster = LocalToaster.current
    Box(
        modifier = modifier
            .background(bg)
            .clickable {
                if (enabled) onClick()
                else disabledHint?.let { toaster.show(it) }
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = fg)
        badge()
    }
}

/**
 * Full-width rounded option row: label on the left, optional [trailing] slot on the right,
 * optionally clickable. [selected] switches to the primary-container colours (used by the
 * Modifier tab's image-edit picker).
 */
@Composable
fun OptionCard(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    // Nested rows (a row inside an already-carded section) pass InnerShape so the corners don't
    // fight the card around them.
    shape: Shape = CardShape,
    trailing: (@Composable () -> Unit)? = null
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = content,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container, modifier = modifier.fillMaxWidth()) { row() }
    } else {
        Surface(shape = shape, color = container, modifier = modifier.fillMaxWidth()) { row() }
    }
}

/**
 * A tappable colour row: label on the left, a circular swatch of [color] on the right. Used by
 * the global options, the modifier tab and the icon browser — each of which used to carry its
 * own byte-identical copy.
 */
@Composable
fun ColorRow(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    onClick: () -> Unit
) {
    OptionCard(
        label = label,
        modifier = modifier,
        onClick = onClick,
        shape = shape,
        trailing = {
            Surface(
                shape = CircleShape,
                color = color,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(28.dp)
            ) {}
        }
    )
}

/** Card container for a group of related controls (sliders, switches, hint text). */
@Composable
fun OptionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

/**
 * Slider with a header row: label on the left, an optional formatted value (e.g. "120%") on the
 * right. [centered] uses the M3 centered track — for ranges balanced around a middle value.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String? = null,
    centered: Boolean = false,
    // For callers persisting the value somewhere expensive (DataStore): fires once on release
    // instead of on every drag tick.
    onValueChangeFinished: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (valueLabel != null) {
            // Small tonal badge instead of bare primary text — the value reads as a chip.
            Surface(shape = SwatchShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
    if (centered) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            track = { SliderDefaults.CenteredTrack(sliderState = it) }
        )
    } else {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

/**
 * The shared indeterminate wavy loading bar (primary over the surfaceVariant track) used while
 * browsers and grids resolve their content — callers only position it.
 */
@Composable
fun WavyLoadingBar(modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}
