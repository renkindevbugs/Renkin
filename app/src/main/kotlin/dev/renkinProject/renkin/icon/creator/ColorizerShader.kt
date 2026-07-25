package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Gradient construction lives here so the icon generator and the live editor preview paint the
 * exact same thing — a second implementation in the UI would drift from the built output.
 */
fun buildColorizerShader(
    colors: List<Int>,
    type: GradientType,
    angle: Float,
    width: Int,
    height: Int
): Shader {
    val centerX = width / 2f
    val centerY = height / 2f
    // A single stop is not a gradient; duplicate it so the shader still paints that flat colour.
    val stops = if (colors.size >= MIN_GRADIENT_STOPS) colors else colors + colors
    val palette = stops.toIntArray()
    return when (type) {
        GradientType.LINEAR -> {
            val angleRadians = Math.toRadians(
                (normalizeGradientAngle(angle) % 360f).toDouble()
            )
            // Match the dial used by design tools: 0° points up, increasing clockwise.
            val directionX = sin(angleRadians).toFloat()
            val directionY = -cos(angleRadians).toFloat()
            val halfSpan = abs(directionX) * centerX + abs(directionY) * centerY
            LinearGradient(
                centerX - directionX * halfSpan,
                centerY - directionY * halfSpan,
                centerX + directionX * halfSpan,
                centerY + directionY * halfSpan,
                palette,
                // Null positions spread the stops evenly, which is what the editor promises.
                null,
                Shader.TileMode.CLAMP
            )
        }
        GradientType.RADIAL -> RadialGradient(
            centerX,
            centerY,
            hypot(centerX, centerY),
            palette,
            null,
            Shader.TileMode.CLAMP
        )
    }
}

/**
 * Applies [style] to [source] the way the generator's colourize step does — gradient through the
 * alpha mask, monochrome, solid fill or tint — and returns a new bitmap for the editor preview.
 * Everything after colourizing (shape, scale, outline, background) is deliberately left out.
 */
fun colorizeSampleBitmap(
    source: Bitmap,
    style: ColorizerStyle,
    // Area the gradient spans; null uses the whole bitmap. A segment gradient must sweep across
    // the segment, not the icon, or a small region only ever shows a sliver of it.
    gradientBounds: android.graphics.Rect? = null
): Bitmap {
    val gradientWidth = gradientBounds?.width() ?: source.width
    val gradientHeight = gradientBounds?.height() ?: source.height
    fun gradientShader() = buildColorizerShader(
        style.allGradientColors,
        style.gradientType,
        style.gradientAngle,
        gradientWidth,
        gradientHeight
    ).apply {
        gradientBounds?.let { bounds ->
            setLocalMatrix(
                android.graphics.Matrix().apply {
                    setTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                }
            )
        }
    }

    if (style.mode == ColorizerMode.GRADIENT) {
        val base = if (style.monochrome) monochromeBitmap(source, style.inverse) else source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val solidFill = style.flat && !style.monochrome
        val drawGradient = {
            canvas.drawRect(
                0f,
                0f,
                source.width.toFloat(),
                source.height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = gradientShader()
                    if (!solidFill) xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                }
            )
        }
        val drawArtwork = {
            canvas.drawBitmap(
                base,
                0f,
                0f,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    if (solidFill) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
            )
        }
        if (solidFill) {
            drawGradient()
            drawArtwork()
        } else {
            drawArtwork()
            drawGradient()
        }
        return if (style.inverse && !style.monochrome) invertBitmapColors(result) else result
    }

    // Monochrome ignores the picked colour entirely, matching colorizeImage().
    if (style.monochrome) return monochromeBitmap(source, style.inverse)

    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    // Solid fill replaces the artwork's colours; the default tint multiplies with them.
    val blend = if (style.flat) PorterDuff.Mode.SRC_IN else PorterDuff.Mode.MULTIPLY
    Canvas(result).drawBitmap(
        source,
        0f,
        0f,
        Paint().apply {
            colorFilter = PorterDuffColorFilter(
                if (style.inverse) invertArgb(style.firstColor) else style.firstColor,
                blend
            )
        }
    )
    return if (style.inverse) invertBitmapColors(result) else result
}

/**
 * Applies [layers] in order: every layer colourizes the whole icon with its own style, then only
 * the pixels its regions select are kept. Matching runs against [source] throughout, so a later
 * layer still finds the colours the user picked them by.
 */
fun applySegmentLayers(source: Bitmap, layers: List<SegmentLayer>): Bitmap {
    var current = source
    for (layer in layers) {
        if (layer.targets.isEmpty()) continue
        val bounds = segmentBounds(source, layer.targets, layer.tolerance)
        val colorized = colorizeSampleBitmap(current, layer.style, bounds)
        current = mergeSegmentLayer(source, current, colorized, layer.targets, layer.tolerance)
    }
    return current
}
