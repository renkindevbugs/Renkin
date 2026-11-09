package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.ui.theme.CardShape
import kotlin.math.roundToInt

/** Small operation-owned source; older devices receive the blur before it reaches the UI. */
@WorkerThread
internal fun prepareEditorBackdrop(icon: Bitmap): Bitmap {
    val scale = 128f / maxOf(icon.width, icon.height)
    val width = (icon.width * scale).roundToInt().coerceAtLeast(1)
    val height = (icon.height * scale).roundToInt().coerceAtLeast(1)
    val sample = Bitmap.createScaledBitmap(icon, width, height, true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return sample
    var pixels = IntArray(width * height)
    sample.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(2) {
        pixels = blurBackdrop(pixels, width, height, horizontal = true)
        pixels = blurBackdrop(pixels, width, height, horizontal = false)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun blurBackdrop(source: IntArray, width: Int, height: Int, horizontal: Boolean): IntArray {
    // Two radius-12 box passes approximate the 16dp CSS blur at the 360dp reference width.
    val radius = 12
    return IntArray(source.size) { index ->
        val x = index % width
        val y = index / width
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val px = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
            val py = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
            val color = source[py * width + px]
            val weight = AndroidColor.alpha(color)
            alpha += weight
            red += AndroidColor.red(color) * weight
            green += AndroidColor.green(color) * weight
            blue += AndroidColor.blue(color) * weight
        }
        if (alpha == 0) AndroidColor.TRANSPARENT
        else AndroidColor.argb(alpha / (radius * 2 + 1), red / alpha, green / alpha, blue / alpha)
    }
}

@Composable
internal fun FrostedComparisonPanel(
    backdrop: Bitmap?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val bitmap = remember(backdrop) { backdrop?.asImageBitmap() }
    ElevatedCard(
        modifier = modifier,
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh)
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (bitmap != null) {
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.92f).clipToBounds()) {
                        FrostedBackdropArt(bitmap, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(colors.surfaceContainerHigh.copy(alpha = 0.30f)))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.47f to Color.Transparent,
                            1f to colors.surfaceContainerHigh
                        )))
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun FrostedBackdropArt(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    val saturation = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.2f) }) }
    val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(16.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    } else Modifier
    Layout(
        modifier = modifier,
        content = {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = saturation,
                modifier = Modifier.graphicsLayer {
                    scaleX = 2.2f
                    scaleY = 2.2f
                    alpha = 0.85f
                }.then(blur).fillMaxSize()
            )
        }
    ) { measurables, constraints ->
        // The CSS source is square and 65% of the card width, even when taller than its window.
        val side = (constraints.maxWidth * 0.65f).roundToInt().coerceAtLeast(1)
        val artwork = measurables.single().measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            artwork.place((constraints.maxWidth - side) / 2, (constraints.maxHeight - side) / 2)
        }
    }
}
