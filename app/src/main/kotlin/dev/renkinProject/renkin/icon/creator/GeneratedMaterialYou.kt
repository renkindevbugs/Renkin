package dev.renkinProject.renkin.icon.creator

import android.graphics.Color
import kotlin.math.hypot

private const val OPAQUE_ALPHA = 128
// Half-width of the soft edge around the two-tone threshold, in luminance.
private const val EDGE_SOFTNESS = 0.06f
// The ring the background is read from, as a fraction of the icon's half-size.
private const val BORDER_RING = 0.8f
private const val DARK_PLATE_MAJORITY = 1.5f
private const val OTSU_BINS = 64

/**
 * How much each pixel belongs to the generated Material You foreground, 0..1. The artwork is split
 * into two tones at its own luminance threshold, and the tone its outer ring mostly has is the
 * background. A dark logo on a light plate therefore keeps the dark part as foreground (as before),
 * while a light logo on a dark or colourful plate, or on a gradient, now becomes the foreground
 * instead of the plate itself.
 */
internal fun materialYouForegroundCoverage(pixels: IntArray, width: Int, height: Int): FloatArray {
    val luminance = FloatArray(pixels.size) { luminanceOf(pixels[it]) }
    val histogram = IntArray(OTSU_BINS)
    var opaqueCount = 0
    for (index in pixels.indices) {
        if (Color.alpha(pixels[index]) < OPAQUE_ALPHA) continue
        histogram[otsuBin(luminance[index])]++
        opaqueCount++
    }
    val coverage = FloatArray(pixels.size)
    if (opaqueCount == 0) return coverage
    val threshold = otsuThreshold(histogram, opaqueCount)
    val backgroundIsLight = borderIsLight(pixels, luminance, width, height, threshold)
    for (index in pixels.indices) {
        val light = smoothStep(threshold - EDGE_SOFTNESS, threshold + EDGE_SOFTNESS, luminance[index])
        val foreground = if (backgroundIsLight) 1f - light else light
        coverage[index] = foreground * Color.alpha(pixels[index]) / 255f
    }
    return coverage
}

private fun borderIsLight(pixels: IntArray, luminance: FloatArray, width: Int, height: Int, threshold: Float): Boolean {
    val centerX = width / 2f
    val centerY = height / 2f
    val ring = minOf(centerX, centerY) * BORDER_RING
    var light = 0
    var dark = 0
    for (y in 0 until height) for (x in 0 until width) {
        val index = y * width + x
        if (Color.alpha(pixels[index]) < OPAQUE_ALPHA) continue
        if (hypot(x - centerX, y - centerY) < ring) continue
        if (luminance[index] >= threshold) light++ else dark++
    }
    // Transparent artwork has no plate: transparent pixels are the background, which reads as
    // light so dark artwork keeps being the foreground. A plate only counts as dark when it
    // clearly is; an ambiguous ring keeps that established dark-is-foreground behaviour.
    return light + dark == 0 || dark < light * DARK_PLATE_MAJORITY
}

private fun otsuBin(luminance: Float): Int = (luminance * (OTSU_BINS - 1)).toInt().coerceIn(0, OTSU_BINS - 1)

/** Otsu's method over [OTSU_BINS] luminance bins of [count] pixels: the split that best separates two tones. */
private fun otsuThreshold(histogram: IntArray, count: Int): Float {
    val bins = OTSU_BINS
    val total = count.toFloat()
    val weightedSum = histogram.indices.sumOf { it.toDouble() * histogram[it] }
    var backgroundWeight = 0f
    var backgroundSum = 0.0
    var best = -1.0
    var bestThreshold = 0.5f
    for (bin in 0 until bins) {
        backgroundWeight += histogram[bin]
        if (backgroundWeight == 0f) continue
        val foregroundWeight = total - backgroundWeight
        if (foregroundWeight == 0f) break
        backgroundSum += bin.toDouble() * histogram[bin]
        val meanBackground = backgroundSum / backgroundWeight
        val meanForeground = (weightedSum - backgroundSum) / foregroundWeight
        val between = backgroundWeight.toDouble() * foregroundWeight * (meanBackground - meanForeground).let { it * it }
        if (between > best) {
            best = between
            // Midway between both tones, so clean two-colour art does not split at one edge.
            bestThreshold = ((meanBackground + meanForeground) / 2.0 / (bins - 1)).toFloat()
        }
    }
    return bestThreshold
}

private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun luminanceOf(color: Int): Float =
    (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
