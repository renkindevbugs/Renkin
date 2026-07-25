@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.colorizeSampleBitmap
import dev.renkinProject.renkin.ui.theme.IconShape as IconTileShape
import dev.renkinProject.renkin.ui.theme.DialogShape
import dev.renkinProject.renkin.ui.theme.InnerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Live preview redraws are debounced by this much — dragging the angle dial fires continuously. */
private const val PreviewDebounceMs = 80L

/**
 * Colour/gradient editor hosted in a bottom sheet with a live preview docked above it. Used by
 * Colorize and by the outline colour, hence the caller-supplied [title]. The draft is local, so
 * the caller's state only changes on Apply and Cancel really cancels.
 */
@Composable
internal fun ColorStyleSheet(
    title: String,
    initialStyle: ColorizerStyle,
    sampleBitmap: Bitmap?,
    onDismiss: () -> Unit,
    onApply: (ColorizerStyle) -> Unit,
    showSingleColorEffects: Boolean = true,
    // Preferred preview source: the caller's real generation pipeline. Without one the sheet
    // falls back to colourizing [sampleBitmap], which skips shape, scale and outline.
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)? = null
) {
    var draft by remember { mutableStateOf(initialStyle) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column {
            ColorStyleSheetHeader(
                title = title,
                sampleBitmap = sampleBitmap,
                style = draft,
                renderPreview = renderPreview
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ColorizerStyleEditor(
                    style = draft,
                    onStyleChange = { draft = it },
                    sampleBitmap = sampleBitmap,
                    showSingleColorEffects = showSingleColorEffects
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.colorizeCancel)) }
                Button(onClick = { onApply(draft) }) { Text(stringResource(R.string.apply)) }
            }
        }
    }
}

@Composable
private fun ColorStyleSheetHeader(
    title: String,
    sampleBitmap: Bitmap?,
    style: ColorizerStyle,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?
) {
    // Global options has no single icon to preview; there the swatch alone carries the state.
    val previewable = renderPreview != null || sampleBitmap != null
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var enlarged by remember { mutableStateOf(false) }

    // Rendering happens off the main thread and is dropped when the sheet closes; the bitmap is
    // deliberately kept out of rememberSaveable (Bitmaps must not ride a saved-state bundle).
    LaunchedEffect(sampleBitmap, style, renderPreview) {
        delay(PreviewDebounceMs)
        preview = if (renderPreview != null) {
            renderPreview(style)
        } else {
            val source = sampleBitmap ?: return@LaunchedEffect
            withContext(Dispatchers.Default) { colorizeSampleBitmap(source, style) }
        }
    }
    DisposableEffect(Unit) { onDispose { preview = null } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(InnerShape)
                .colorizerSwatch(style)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.colorizeSheetHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Same affordance as the edit dialog's New slot: tap the preview to judge it big.
        if (previewable) {
            Surface(
                shape = IconTileShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .size(64.dp)
                    .clickable(enabled = preview != null) { enlarged = true }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    preview?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.iconNew),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(4.dp).fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    val enlargedBitmap = preview
    if (enlarged && enlargedBitmap != null) {
        Dialog(onDismissRequest = { enlarged = false }) {
            Surface(
                shape = DialogShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { enlarged = false }
            ) {
                Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = enlargedBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.iconNew),
                        modifier = Modifier.size(240.dp)
                    )
                }
            }
        }
    }
}

/**
 * Paints [style] as its own swatch: the real gradient at its real angle (or the radial version),
 * so a swatch never disagrees with the icon it describes.
 */
@Composable
internal fun Modifier.colorizerSwatch(style: ColorizerStyle): Modifier {
    val gradient = style.mode == ColorizerMode.GRADIENT
    val colors = if (gradient) {
        style.allGradientColors
    } else {
        listOf(style.firstColor, style.firstColor)
    }
    // Animating each stop keeps the One color / Gradient switch from popping.
    val animated = colors.map { animateColorAsState(Color(it), label = "colorizerStop").value }
    // The angle only reads correctly once the draw size is known, hence drawBehind over background.
    val angle by animateFloatAsState(style.gradientAngle, label = "colorizerAngle")
    val radial = gradient && style.gradientType == GradientType.RADIAL
    return drawBehind {
        val brush = if (radial) {
            Brush.radialGradient(
                colors = animated,
                center = center,
                radius = hypot(size.width / 2f, size.height / 2f)
            )
        } else {
            // Same convention as the dial and the generator: 0° points up, clockwise.
            val radians = Math.toRadians(angle.toDouble())
            val directionX = sin(radians).toFloat()
            val directionY = -cos(radians).toFloat()
            val halfSpan = abs(directionX) * size.width / 2f + abs(directionY) * size.height / 2f
            Brush.linearGradient(
                colors = animated,
                start = Offset(
                    center.x - directionX * halfSpan,
                    center.y - directionY * halfSpan
                ),
                end = Offset(
                    center.x + directionX * halfSpan,
                    center.y + directionY * halfSpan
                )
            )
        }
        drawRect(brush)
    }
}

/** Row that opens [ColorStyleSheet], previewing the current colours in its trailing swatch. */
@Composable
internal fun ColorStyleCard(
    label: String,
    style: ColorizerStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OptionCard(
        label = label,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 28.dp)
                    .clip(InnerShape)
                    .colorizerSwatch(style)
            )
        }
    )
}
