package dev.renkinProject.renkin.feature.launch

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val HARD_CAP_MS = 3500L
private const val SYMBOL_SIZE_FRACTION = 0.62f
private const val SYMBOL_CENTER_Y = 0.46f
/** Platform and core-splashscreen size of a splash icon without an icon background. */
private val SplashIconSize = 288.dp

/**
 * Plum launch overlay drawn above the startup shell: the launcher symbol is forged by fire, then
 * the overlay fades into the app. It never delays startup — the shell loads underneath — and a tap
 * or the [HARD_CAP_MS] cap ends it early. It starts exactly where the launch window leaves off:
 * the static gold symbol at the splash icon's geometry, which grows while it ignites.
 */
@Composable
fun LaunchFireOverlay(
    playback: LaunchFirePlayback,
    mask: LaunchFireMask?,
    particleCapacity: Int,
    onSettled: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overlayAlpha = remember { Animatable(1f) }
    var skipRequested by remember { mutableStateOf(false) }
    val engine = remember(mask) { mask?.let { LaunchFireEngine(it, particleCapacity) } }
    var simulatedMs by remember { mutableFloatStateOf(0f) }
    val currentOnSettled by rememberUpdatedState(onSettled)
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        delay(HARD_CAP_MS)
        skipRequested = true
    }

    LaunchedEffect(playback, engine, skipRequested) {
        when {
            skipRequested -> {
                currentOnSettled()
                overlayAlpha.animateTo(0f, tween(150))
            }
            playback == LaunchFirePlayback.FadeOnly -> {
                currentOnSettled()
                overlayAlpha.animateTo(0f, tween(200))
            }
            engine == null -> return@LaunchedEffect
            else -> {
                var lastFrame = withFrameNanos { it }
                while (!engine.finished) {
                    withFrameNanos { now ->
                        engine.advance((now - lastFrame) / 1_000_000_000f)
                        lastFrame = now
                        simulatedMs = engine.elapsedMs
                    }
                }
                // The home screen composes now, behind a symbol that no longer moves: its
                // main-thread stall (~550 ms on device) froze the fire when it came mid-animation.
                // The fade waits for smooth frames again, or it would play as two or three jumps.
                currentOnSettled()
                awaitSmoothFrames()
                overlayAlpha.animateTo(0f, tween(250))
            }
        }
        currentOnFinished()
    }

    val symbolBitmap = remember(mask) { mask?.let { createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) } }
    val symbolImage = remember(symbolBitmap) { symbolBitmap?.asImageBitmap() }
    val sprites = remember { FireSprites() }
    val symbolShape = rememberVectorPainter(ImageVector.vectorResource(R.drawable.launch_splash_symbol))

    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .pointerInput(Unit) { detectTapGestures(onTap = { skipRequested = true }) }
    ) {
        drawRect(LaunchPlum)
        // Reading the simulated time subscribes this draw to every simulation step.
        if (simulatedMs < 0f || engine == null || mask == null || symbolBitmap == null || symbolImage == null) {
            val splashIcon = SplashIconSize.toPx()
            drawSymbolShape(symbolShape, (size.width - splashIcon) / 2f, (size.height - splashIcon) / 2f, splashIcon)
            return@Canvas
        }
        if (engine.paintSymbol()) {
            symbolBitmap.setPixels(engine.pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        }
        drawFire(engine, mask, symbolImage, symbolShape, sprites)
    }
}

private const val SMOOTH_FRAME_NANOS = 34_000_000L
private const val SMOOTH_FRAMES_NEEDED = 3
private const val MAX_SETTLE_WAIT_NANOS = 900_000_000L

private suspend fun awaitSmoothFrames() {
    var last = withFrameNanos { it }
    var waited = 0L
    var smooth = 0
    while (smooth < SMOOTH_FRAMES_NEEDED && waited < MAX_SETTLE_WAIT_NANOS) {
        withFrameNanos { now ->
            val frame = now - last
            smooth = if (frame < SMOOTH_FRAME_NANOS) smooth + 1 else 0
            waited += frame
            last = now
        }
    }
}

/** The vector symbol (the splash icon's drawable) over a square launcher-layer area. */
private fun DrawScope.drawSymbolShape(shape: VectorPainter, layerLeft: Float, layerTop: Float, layerSize: Float) {
    translate(layerLeft, layerTop) {
        with(shape) { draw(Size(layerSize, layerSize)) }
    }
}

private fun DrawScope.drawFire(
    engine: LaunchFireEngine,
    mask: LaunchFireMask,
    colors: ImageBitmap,
    shape: VectorPainter,
    sprites: FireSprites
) {
    val p = engine.progress
    val splashIcon = SplashIconSize.toPx()
    val splashScale = splashIcon / mask.sourceSize
    val splashLeft = (size.width - splashIcon) / 2f + mask.cropLeft * splashScale
    val splashTop = (size.height - splashIcon) / 2f + mask.cropTop * splashScale
    val finalScale = min(size.width, size.height) * SYMBOL_SIZE_FRACTION / max(mask.width, mask.height)
    val finalLeft = (size.width - mask.width * finalScale) / 2f
    val finalTop = size.height * SYMBOL_CENTER_Y - mask.height * finalScale / 2f

    val grow = LaunchFireTimeline.grow(p)
    val toScreen = mix(splashScale, finalScale, grow)
    val left = mix(splashLeft, finalLeft, grow)
    val top = mix(splashTop, finalTop, grow)
    val symbolWidth = mask.width * toScreen
    val symbolHeight = mask.height * toScreen
    val center = Offset(left + symbolWidth / 2f, top + symbolHeight / 2f)

    val glow = LaunchFireTimeline.glow(p)
    if (glow > 0.001f) {
        drawRect(
            Brush.radialGradient(
                0f to Color(0xFFE0703A).copy(alpha = 0.42f * glow),
                0.45f to Color(0xFF963228).copy(alpha = 0.18f * glow),
                1f to Color.Transparent,
                center = center,
                radius = max(size.width, size.height) * 0.55f
            )
        )
    }

    // The vector gives the sharp outline; the low-resolution colour field only tints it (SrcAtop),
    // so upscaling it never shows pixels on the edges.
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(left, top, left + symbolWidth, top + symbolHeight), androidx.compose.ui.graphics.Paint())
        drawSymbolShape(shape, left - mask.cropLeft * toScreen, top - mask.cropTop * toScreen, mask.sourceSize * toScreen)
        drawImage(
            colors,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(symbolWidth.roundToInt(), symbolHeight.roundToInt()),
            blendMode = BlendMode.SrcAtop,
            filterQuality = FilterQuality.Medium
        )
        canvas.restore()
    }

    for (i in 0 until engine.count) {
        val age = engine.ageFraction(i)
        val sprite: ImageBitmap
        val alpha: Float
        val radius: Float
        if (engine.kind[i] == FLAME) {
            sprite = sprites.flames[min(sprites.flames.lastIndex, (age * sprites.flames.size).toInt())]
            alpha = (1f - age) * 0.75f
            radius = engine.radius[i] * toScreen * (1f + age * 0.4f)
        } else {
            sprite = sprites.ember
            alpha = sin(age * PI.toFloat()) * 0.95f
            radius = engine.radius[i] * toScreen * 2f
        }
        val diameter = (radius * 2f).roundToInt()
        if (diameter < 1) continue
        drawImage(
            sprite,
            dstOffset = IntOffset((left + engine.x[i] * toScreen - radius).roundToInt(), (top + engine.y[i] * toScreen - radius).roundToInt()),
            dstSize = IntSize(diameter, diameter),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.Plus
        )
    }
}

/** Soft radial dots, baked once; flames step through them from white-hot to soot as they age. */
private class FireSprites {
    val flames = listOf(0xFFF6D6, 0xFFC45A, 0xF6802C, 0xC43E1E, 0x5A1E28).map { radialSprite(it, 64) }
    val ember = radialSprite(0xFFBE6E, 16)
}

private fun radialSprite(rgb: Int, size: Int): ImageBitmap {
    fun withAlpha(alpha: Float) = ((alpha * 255f).roundToInt() shl 24) or rgb
    val half = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            half, half, half,
            intArrayOf(withAlpha(1f), withAlpha(0.55f), withAlpha(0f)),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return bitmap.asImageBitmap()
}
