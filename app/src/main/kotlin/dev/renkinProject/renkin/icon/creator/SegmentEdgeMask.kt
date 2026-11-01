package dev.renkinProject.renkin.icon.creator

import android.graphics.Color
import kotlin.math.sqrt

// An anti-aliased edge pixel lies on the colour line between the segment and its neighbour; one
// further off that line than this (0..1 of the RGB diagonal) is detail, not a blended edge.
private const val EDGE_LINE_TOLERANCE = 0.12f
private const val RGB_DIAGONAL = 441.673f

/**
 * How much of each pixel belongs to the segments [targets] selects, 0..1. Pixels within
 * [tolerance] count fully, exactly as [matchesSegment] decides. A pixel just outside a selected
 * region whose colour is a blend of the segment and its outside neighbour counts by that blend,
 * so a recoloured or erased region keeps the artwork's anti-aliased edge instead of a
 * stair-stepped one. Pixels away from a selected region are never touched.
 */
fun segmentEdgeMask(pixels: IntArray, width: Int, height: Int, targets: List<Int>, tolerance: Float): FloatArray {
    val mask = FloatArray(pixels.size)
    if (targets.isEmpty()) {
        mask.fill(1f)
        return mask
    }
    val selected = BooleanArray(pixels.size) { Color.alpha(pixels[it]) != 0 && matchesSegment(pixels[it], targets, tolerance) }
    for (index in pixels.indices) {
        if (selected[index]) {
            mask[index] = 1f
            continue
        }
        if (Color.alpha(pixels[index]) == 0) continue
        mask[index] = edgeCoverage(pixels, selected, width, height, index, targets)
    }
    return mask
}

private fun edgeCoverage(
    pixels: IntArray,
    selected: BooleanArray,
    width: Int,
    height: Int,
    index: Int,
    targets: List<Int>
): Float {
    var touchesSelection = false
    forEachNeighbour(index, width, height) { if (selected[it]) touchesSelection = true }
    if (!touchesSelection) return 0f
    val pixel = pixels[index]
    val target = targets.minBy { distance(pixel, it) }
    var outside: Int? = null
    var outsideDistance = -1f
    forEachNeighbour(index, width, height) { neighbour ->
        if (!selected[neighbour] && Color.alpha(pixels[neighbour]) != 0) {
            // The outside colour the edge blends into: the neighbour least like the segment.
            val farness = distance(pixels[neighbour], target)
            if (farness > outsideDistance) {
                outsideDistance = farness
                outside = pixels[neighbour]
            }
        }
    }
    return outside?.let { blendFraction(pixel, target, it) } ?: 0f
}

private inline fun forEachNeighbour(index: Int, width: Int, height: Int, action: (Int) -> Unit) {
    val x = index % width
    val y = index / width
    for (dy in -1..1) for (dx in -1..1) {
        val nx = x + dx
        val ny = y + dy
        if ((dx != 0 || dy != 0) && nx in 0 until width && ny in 0 until height) action(ny * width + nx)
    }
}

/** Where [pixel] sits on the colour line from [outside] (0) to [target] (1), 0 when it is off it. */
private fun blendFraction(pixel: Int, target: Int, outside: Int): Float {
    val lineR = (Color.red(target) - Color.red(outside)).toFloat()
    val lineG = (Color.green(target) - Color.green(outside)).toFloat()
    val lineB = (Color.blue(target) - Color.blue(outside)).toFloat()
    val lengthSquared = lineR * lineR + lineG * lineG + lineB * lineB
    if (lengthSquared == 0f) return 0f
    val r = (Color.red(pixel) - Color.red(outside)).toFloat()
    val g = (Color.green(pixel) - Color.green(outside)).toFloat()
    val b = (Color.blue(pixel) - Color.blue(outside)).toFloat()
    val fraction = ((r * lineR + g * lineG + b * lineB) / lengthSquared).coerceIn(0f, 1f)
    val offR = r - fraction * lineR
    val offG = g - fraction * lineG
    val offB = b - fraction * lineB
    val offLine = sqrt(offR * offR + offG * offG + offB * offB)
    return if (offLine <= EDGE_LINE_TOLERANCE * RGB_DIAGONAL) fraction else 0f
}

private fun distance(a: Int, b: Int): Float {
    val dr = (Color.red(a) - Color.red(b)).toFloat()
    val dg = (Color.green(a) - Color.green(b)).toFloat()
    val db = (Color.blue(a) - Color.blue(b)).toFloat()
    return sqrt(dr * dr + dg * dg + db * db)
}

/** [from] blended toward [to] by [weight], in premultiplied space so transparent edges stay clean. */
internal fun blendPremultiplied(from: Int, to: Int, weight: Float): Int {
    if (weight <= 0f) return from
    if (weight >= 1f) return to
    val fromAlpha = Color.alpha(from) / 255f
    val toAlpha = Color.alpha(to) / 255f
    val alpha = fromAlpha + (toAlpha - fromAlpha) * weight
    if (alpha <= 0f) return Color.TRANSPARENT
    fun channel(a: Int, b: Int): Int {
        val premultiplied = a * fromAlpha + (b * toAlpha - a * fromAlpha) * weight
        return (premultiplied / alpha).coerceIn(0f, 255f).toInt()
    }
    return Color.argb(
        (alpha * 255f + 0.5f).toInt().coerceIn(0, 255),
        channel(Color.red(from), Color.red(to)),
        channel(Color.green(from), Color.green(to)),
        channel(Color.blue(from), Color.blue(to))
    )
}
