package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One colour region of an icon: its representative colour and how much of the icon it covers. */
data class ColorSegment(val color: Int, val coverage: Float)

/** Segment counts the picker offers; the default suits most icons without shredding gradients. */
const val SEGMENT_COUNT_DEFAULT = 6
const val SEGMENT_COUNT_MIN = 2
const val SEGMENT_COUNT_MAX = 12

/**
 * How far (0..1 of the RGB cube's diagonal) a pixel may sit from a selected segment colour and
 * still be repainted. Shading inside one flat region varies more than people expect, so the
 * default is generous.
 */
const val SEGMENT_TOLERANCE_DEFAULT = 0.18f

// Opaque enough that the pixel's unpremultiplied RGB describes a real colour. Antialiased
// fringes are matched anyway (they sit between two segment colours), they just don't vote.
private const val SEGMENT_MIN_ALPHA = 128

// A fixed sweep is plenty for a few thousand samples and keeps generation time predictable.
private const val KMEANS_ITERATIONS = 8
private const val KMEANS_MAX_SAMPLES = 4096

/**
 * Groups [icon]'s visible pixels into at most [count] colour regions (k-means over RGB), sorted
 * by coverage. Used by the Colorize picker so "recolour only the white parts" is one tap.
 */
fun segmentColors(icon: Bitmap, count: Int = SEGMENT_COUNT_DEFAULT): List<ColorSegment> {
    val clusters = count.coerceIn(SEGMENT_COUNT_MIN, SEGMENT_COUNT_MAX)
    val pixels = IntArray(icon.width * icon.height)
    icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)

    // Sample rather than cluster every pixel: a 500px icon is a quarter million points and the
    // centroids stop moving long before that many are needed.
    val stride = maxOf(1, pixels.size / KMEANS_MAX_SAMPLES)
    val samples = ArrayList<Int>(minOf(pixels.size, KMEANS_MAX_SAMPLES))
    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        if (Color.alpha(pixel) >= SEGMENT_MIN_ALPHA) samples.add(pixel)
        index += stride
    }
    if (samples.isEmpty()) return emptyList()

    // Seed on the spread of the samples so the first pass already separates light from dark.
    val sorted = samples.sortedBy { luminanceOf(it) }
    val centroids = FloatArray(clusters * 3)
    for (cluster in 0 until clusters) {
        val seed = sorted[(sorted.size - 1) * cluster / maxOf(1, clusters - 1)]
        centroids[cluster * 3] = Color.red(seed).toFloat()
        centroids[cluster * 3 + 1] = Color.green(seed).toFloat()
        centroids[cluster * 3 + 2] = Color.blue(seed).toFloat()
    }

    val assignment = IntArray(samples.size)
    repeat(KMEANS_ITERATIONS) {
        samples.forEachIndexed { i, pixel ->
            assignment[i] = nearestCentroid(pixel, centroids, clusters)
        }
        val sums = FloatArray(clusters * 3)
        val counts = IntArray(clusters)
        samples.forEachIndexed { i, pixel ->
            val cluster = assignment[i]
            sums[cluster * 3] += Color.red(pixel).toFloat()
            sums[cluster * 3 + 1] += Color.green(pixel).toFloat()
            sums[cluster * 3 + 2] += Color.blue(pixel).toFloat()
            counts[cluster]++
        }
        for (cluster in 0 until clusters) {
            if (counts[cluster] == 0) continue
            for (channel in 0..2) {
                centroids[cluster * 3 + channel] = sums[cluster * 3 + channel] / counts[cluster]
            }
        }
    }

    val counts = IntArray(clusters)
    assignment.forEach { counts[it]++ }
    return (0 until clusters)
        .filter { counts[it] > 0 }
        .map { cluster ->
            ColorSegment(
                color = Color.argb(
                    255,
                    centroids[cluster * 3].roundToInt().coerceIn(0, 255),
                    centroids[cluster * 3 + 1].roundToInt().coerceIn(0, 255),
                    centroids[cluster * 3 + 2].roundToInt().coerceIn(0, 255)
                ),
                coverage = counts[cluster].toFloat() / samples.size
            )
        }
        .sortedByDescending { it.coverage }
}

/**
 * True when [pixel] belongs to one of [targets] within [tolerance]. Segments are stored as plain
 * colours rather than a pixel mask, so a regenerated or rescaled icon still finds its regions.
 */
fun matchesSegment(pixel: Int, targets: List<Int>, tolerance: Float): Boolean {
    if (targets.isEmpty()) return true
    // The RGB cube's diagonal, so tolerance reads as a fraction of "as different as possible".
    val limit = tolerance.coerceIn(0f, 1f) * 441.673f
    val red = Color.red(pixel)
    val green = Color.green(pixel)
    val blue = Color.blue(pixel)
    return targets.any { target ->
        val dr = (red - Color.red(target)).toFloat()
        val dg = (green - Color.green(target)).toFloat()
        val db = (blue - Color.blue(target)).toFloat()
        sqrt(dr * dr + dg * dg + db * db) <= limit
    }
}

/**
 * Keeps [colorized] only where [source]'s pixels match [targets], falling back to the original
 * pixel everywhere else. Returns the bounds of the repainted area, empty when nothing matched.
 */
fun mergeSegmentColorize(
    source: Bitmap,
    colorized: Bitmap,
    targets: List<Int>,
    tolerance: Float
): Bitmap = mergeSegmentLayer(source, source, colorized, targets, tolerance)

/**
 * Takes [colorized] where [matchSource] matches [targets] and [current] everywhere else.
 * Matching always happens against the ORIGINAL artwork: an earlier layer may already have
 * repainted those pixels, and a later layer's colours were picked before that happened.
 */
fun mergeSegmentLayer(
    matchSource: Bitmap,
    current: Bitmap,
    colorized: Bitmap,
    targets: List<Int>,
    tolerance: Float
): Bitmap {
    if (targets.isEmpty()) return colorized
    val width = matchSource.width
    val height = matchSource.height
    if (colorized.width != width || colorized.height != height) return colorized
    if (current.width != width || current.height != height) return colorized

    val matchPixels = IntArray(width * height)
    val currentPixels = IntArray(width * height)
    val colorizedPixels = IntArray(width * height)
    matchSource.getPixels(matchPixels, 0, width, 0, 0, width, height)
    current.getPixels(currentPixels, 0, width, 0, 0, width, height)
    colorized.getPixels(colorizedPixels, 0, width, 0, 0, width, height)

    for (i in matchPixels.indices) {
        val original = matchPixels[i]
        if (Color.alpha(original) == 0 || !matchesSegment(original, targets, tolerance)) {
            colorizedPixels[i] = currentPixels[i]
        }
    }
    return Bitmap.createBitmap(colorizedPixels, width, height, Bitmap.Config.ARGB_8888).apply {
        density = matchSource.density
    }
}

/** Bounds of the pixels [targets] selects, so a gradient can span the segment, not the icon. */
fun segmentBounds(source: Bitmap, targets: List<Int>, tolerance: Float): android.graphics.Rect? {
    if (targets.isEmpty()) return null
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            if (Color.alpha(pixel) == 0) continue
            if (!matchesSegment(pixel, targets, tolerance)) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    return if (right < left || bottom < top) null else android.graphics.Rect(left, top, right + 1, bottom + 1)
}

private fun nearestCentroid(pixel: Int, centroids: FloatArray, clusters: Int): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for (cluster in 0 until clusters) {
        val dr = Color.red(pixel) - centroids[cluster * 3]
        val dg = Color.green(pixel) - centroids[cluster * 3 + 1]
        val db = Color.blue(pixel) - centroids[cluster * 3 + 2]
        val distance = dr * dr + dg * dg + db * db
        if (distance < bestDistance) {
            bestDistance = distance
            best = cluster
        }
    }
    return best
}

private fun luminanceOf(pixel: Int): Float =
    0.213f * Color.red(pixel) + 0.715f * Color.green(pixel) + 0.072f * Color.blue(pixel)
