package dev.renkinProject.renkin.feature.launch

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.renkinProject.renkin.R
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The launcher symbol cropped to its bounds, plus everything the fire needs per pixel:
 * coverage, the time the burn front reaches it, and the colour it cools to.
 */
class LaunchFireMask internal constructor(
    /** Side of the square launcher layer the mask was cropped from; it maps to the splash icon. */
    val sourceSize: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val width: Int,
    val height: Int,
    val coverage: FloatArray,
    val burnTime: FloatArray,
    /** Indices of well-covered pixels sorted by [burnTime]; flames spawn where the front is. */
    val frontOrder: IntArray,
    val finishRed: FloatArray,
    val finishGreen: FloatArray,
    val finishBlue: FloatArray
)

/** Decodes the launcher foreground off the main thread; returns null if it cannot be read. */
internal fun loadLaunchFireMask(resources: Resources, workingSize: Int): LaunchFireMask? {
    val source = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher_foreground) ?: return null
    val scaled = Bitmap.createScaledBitmap(source, workingSize, workingSize, true)
    val argb = IntArray(workingSize * workingSize)
    scaled.getPixels(argb, 0, workingSize, 0, 0, workingSize, workingSize)
    if (scaled !== source) scaled.recycle()
    source.recycle()
    return buildLaunchFireMask(argb, workingSize, workingSize)
}

private const val SPIKE_TIP_X = 0.93f
private const val SPIKE_TIP_Y = 0.03f

/**
 * The foreground PNG has the plum square baked in, so coverage is "how gold" each pixel is
 * rather than its alpha.
 */
internal fun buildLaunchFireMask(argb: IntArray, sourceWidth: Int, sourceHeight: Int): LaunchFireMask? {
    val gold = FloatArray(argb.size)
    var minX = sourceWidth
    var minY = sourceHeight
    var maxX = -1
    var maxY = -1
    for (index in argb.indices) {
        val pixel = argb[index]
        val alpha = (pixel ushr 24) / 255f
        val red = (pixel shr 16) and 0xFF
        val blue = pixel and 0xFF
        val value = clamp01((red - 40) / 165f) * clamp01((red - blue) / 90f) * alpha
        gold[index] = value
        if (value > 0.05f) {
            val x = index % sourceWidth
            val y = index / sourceWidth
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
    }
    if (maxX < 0) return null

    val padding = max(2, sourceWidth / 72)
    val left = max(0, minX - padding)
    val top = max(0, minY - padding)
    val width = min(sourceWidth, maxX + padding + 1) - left
    val height = min(sourceHeight, maxY + padding + 1) - top
    val size = width * height
    val coverage = FloatArray(size)
    val burnTime = FloatArray(size)
    val finishRed = FloatArray(size)
    val finishGreen = FloatArray(size)
    val finishBlue = FloatArray(size)

    var minBurn = Float.MAX_VALUE
    var maxBurn = -Float.MAX_VALUE
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        val nx = x.toFloat() / width
        val ny = y.toFloat() / height
        coverage[i] = gold[(y + top) * sourceWidth + x + left]
        // Empty pixels are never painted; skipping their noise keeps the load off the launch path.
        if (coverage[i] < 0.01f) continue
        val burn = hypot(nx - SPIKE_TIP_X, ny - SPIKE_TIP_Y) * 0.8f + fractalNoise(nx * 5.5f, ny * 5.5f) * 0.2f
        burnTime[i] = burn
        if (coverage[i] > 0.05f) {
            minBurn = min(minBurn, burn)
            maxBurn = max(maxBurn, burn)
        }
        val finish = clamp01(nx * 0.55f + (1f - ny) * 0.45f)
        finishRed[i] = CopperToGold.red(finish)
        finishGreen[i] = CopperToGold.green(finish)
        finishBlue[i] = CopperToGold.blue(finish)
    }
    val span = (maxBurn - minBurn).takeIf { it > 0f } ?: 1f
    for (i in 0 until size) burnTime[i] = clamp01((burnTime[i] - minBurn) / span)

    // Burn times are non-negative, so their float bits sort like the values: one primitive sort.
    var covered = 0
    for (i in 0 until size) if (coverage[i] > 0.5f) covered++
    if (covered == 0) return null
    val keys = LongArray(covered)
    var next = 0
    for (i in 0 until size) {
        if (coverage[i] > 0.5f) keys[next++] = (burnTime[i].toRawBits().toLong() shl 32) or i.toLong()
    }
    keys.sort()
    val frontOrder = IntArray(covered) { keys[it].toInt() }

    return LaunchFireMask(sourceWidth, left, top, width, height, coverage, burnTime, frontOrder, finishRed, finishGreen, finishBlue)
}

private fun hash(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) ushr 8) / 16777216f
}

private fun valueNoise(x: Float, y: Float): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val u = smoothStep(0f, 1f, x - xi)
    val v = smoothStep(0f, 1f, y - yi)
    val a = hash(xi, yi)
    val b = hash(xi + 1, yi)
    val c = hash(xi, yi + 1)
    val d = hash(xi + 1, yi + 1)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
}

private fun fractalNoise(x: Float, y: Float): Float =
    valueNoise(x, y) * 0.6f + valueNoise(x * 2.1f, y * 2.1f) * 0.3f + valueNoise(x * 4.3f, y * 4.3f) * 0.1f
