package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.get
import dev.renkinProject.renkin.drawable.isAdaptiveIconDrawable
import dev.renkinProject.renkin.extension.newArgbBitmap
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Why "Match app color" cannot offer a layer for the current icon. */
enum class AppColorUnavailable { NOT_ADAPTIVE, NEUTRAL_BACKGROUND, NO_NEUTRAL_LOGO }

/** The app's brand colour read from its adaptive background, or why there is none. */
sealed interface AppBackgroundColor {
    data class Found(val style: ColorizerStyle) : AppBackgroundColor
    data class Missing(val reason: AppColorUnavailable) : AppBackgroundColor
}

/**
 * Ready segment layers: optionally erasing a plate in the app's own background colour, then
 * recolouring the white or black logo. Or why there are none.
 */
sealed interface AppColorMatch {
    data class Available(val layers: List<SegmentLayer>) : AppColorMatch {
        /** The layer that carries the app colour, for the button's swatch. */
        val logo: SegmentLayer get() = layers.last()
    }
    data class Unavailable(val reason: AppColorUnavailable) : AppColorMatch
}

private const val SAMPLE_SIZE = 72
// Opposite samples closer than this (0..1 of the RGB diagonal) read as one flat colour.
private const val GRADIENT_MIN_DISTANCE = 0.08f
private const val GRADIENT_MAX_LINE_ERROR = 0.08f
// Across a gradient the colour sits between its ends; a stripe or step shows one end instead.
private val GRADIENT_MIDDLE = 0.15f..0.85f
// Colour regions this close to the background line, covering this much, are the app's own plate.
private const val PLATE_MAX_DISTANCE = 0.12f
private const val PLATE_MIN_COVERAGE = 0.25
private const val PLATE_EXTRAPOLATION = 0.4f
private const val NEUTRAL_MAX_SATURATION = 0.15f
private const val NEUTRAL_MAX_VALUE_FOR_DARK = 0.15f

/**
 * Reads the colour or two-stop gradient of [appIcon]'s adaptive background. Only the part a
 * launcher shows (the inner 72 of 108 dp) is sampled. Works only on a copy, so the caller's
 * drawable keeps its bounds while the UI may be drawing it; an icon that cannot be copied has
 * no readable colour.
 */
fun appBackgroundColor(appIcon: Drawable): AppBackgroundColor {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !appIcon.isAdaptiveIconDrawable()) {
        return AppBackgroundColor.Missing(AppColorUnavailable.NOT_ADAPTIVE)
    }
    val copy = appIcon.constantState?.newDrawable()?.mutate() as? AdaptiveIconDrawable
        ?: return AppBackgroundColor.Missing(AppColorUnavailable.NOT_ADAPTIVE)
    val background = copy.background
        ?: return AppBackgroundColor.Missing(AppColorUnavailable.NOT_ADAPTIVE)
    val layer = newArgbBitmap(SAMPLE_SIZE, SAMPLE_SIZE) { canvas ->
        background.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        background.draw(canvas)
    }
    val style = backgroundStyleOf(layer)
    return if (style.allGradientColors.all(::isNeutral)) {
        AppBackgroundColor.Missing(AppColorUnavailable.NEUTRAL_BACKGROUND)
    } else AppBackgroundColor.Found(style)
}

/**
 * A solid fill, or a linear gradient between the two most different opposite edge samples.
 * Only a ring near the visible edge is read: some apps put their whole legacy icon, logo
 * included, into the background layer, so its centre is not the background colour.
 */
internal fun backgroundStyleOf(layer: Bitmap): ColorizerStyle {
    val size = min(layer.width, layer.height).toFloat()
    val center = size / 2f
    // Radius inside the launcher's visible 72 dp of the 108 dp layer.
    val reach = size / 2f * (72f / 108f) * 0.85f
    fun sample(dx: Float, dy: Float) = averageAround(layer, (center + dx * reach).toInt(), (center + dy * reach).toInt())
    val diagonal = 0.7071f
    val directions = listOf(
        floatArrayOf(0f, -1f), floatArrayOf(diagonal, -diagonal),
        floatArrayOf(1f, 0f), floatArrayOf(diagonal, diagonal)
    )
    val pairs = directions.map { (dx, dy) -> Triple(sample(-dx, -dy), sample(dx, dy), floatArrayOf(dx, dy)) }
    // A logo crossing the ring shows up as white or black; it is not part of the background.
    val ring = pairs.flatMap { listOf(it.first, it.second) }
    val colourful = ring.filterNot(::isNeutral)
    val solid = ColorizerStyle(firstColor = medoidOf(colourful.ifEmpty { ring }), flat = true)
    val (start, end, direction) = pairs.maxBy { (a, b, _) -> colorDistance(a, b) }
    if (colorDistance(start, end) < GRADIENT_MIN_DISTANCE) return solid
    // A gradient keeps the perpendicular samples on the colour line between its ends; a pattern
    // or picture does not. Eased gradients are fine, so the samples need not sit at the midpoint.
    val across = pairs[(pairs.indexOfFirst { it.third === direction } + 2) % pairs.size]
    val crossing = listOf(across.first, across.second).filterNot(::isNeutral)
    if (crossing.any { sample ->
            val (position, distance) = projectOnColorLine(sample, start, end, 1f)
            distance > GRADIENT_MAX_LINE_ERROR || position !in GRADIENT_MIDDLE
        }) return solid
    // Gradient angles point from the first colour to the last: 0° is up, clockwise.
    val angle = Math.toDegrees(atan2(direction[0].toDouble(), -direction[1].toDouble())).toFloat()
    return ColorizerStyle(
        mode = ColorizerMode.GRADIENT,
        gradientType = GradientType.LINEAR,
        firstColor = start,
        gradientStops = listOf(end),
        gradientPositions = listOf(0f, 1f),
        gradientAngle = normalizeGradientAngle((angle + 360f) % 360f),
        flat = true
    )
}

/**
 * Recolours [icon]'s largest colour region with [background] when that region is white or black,
 * so other colours (an outline, an accent) keep their own colour. When the icon still contains
 * its plate in the app's background colours (an app whose foreground is its whole legacy icon),
 * that plate is erased first: otherwise the recoloured logo would vanish into it.
 */
fun matchAppColor(icon: Bitmap, background: AppBackgroundColor): AppColorMatch {
    val style = when (background) {
        is AppBackgroundColor.Missing -> return AppColorMatch.Unavailable(background.reason)
        is AppBackgroundColor.Found -> background.style
    }
    val segments = segmentColors(icon)
    val plate = segments.filter { !isNeutral(it.color) && onBackground(it.color, style) }
        .takeIf { it.sumOf { segment -> segment.coverage.toDouble() } >= PLATE_MIN_COVERAGE }
        .orEmpty()
    val logo = (segments - plate.toSet()).maxByOrNull { it.coverage }
        ?.takeIf { isNeutral(it.color) }
        ?: return AppColorMatch.Unavailable(AppColorUnavailable.NO_NEUTRAL_LOGO)
    val recolour = SegmentLayer(targets = listOf(logo.color), style = style)
    if (plate.isEmpty()) return AppColorMatch.Available(listOf(recolour))
    val erase = SegmentLayer(
        targets = plate.map { it.color },
        style = ColorizerStyle(firstColor = Color.TRANSPARENT, flat = true)
    )
    return AppColorMatch.Available(listOf(erase, recolour))
}

/** Whether [color] lies on (or just past the ends of) the background's colour line. */
private fun onBackground(color: Int, style: ColorizerStyle): Boolean =
    // Samples sit inside the visible edge, so the real plate runs a little past both ends.
    distanceToColorLine(color, style.firstColor, style.allGradientColors.last(), PLATE_EXTRAPOLATION) <= PLATE_MAX_DISTANCE

/** Distance from [color] to the colour segment [start]..[end], extended by [extension] at both ends. */
private fun distanceToColorLine(color: Int, start: Int, end: Int, extension: Float = 0f): Float =
    projectOnColorLine(color, start, end, extension).second

/** Where [color] falls along [start]..[end] (0..1, clamped to the extension) and how far off it lies. */
private fun projectOnColorLine(color: Int, start: Int, end: Int, extension: Float = 0f): Pair<Float, Float> {
    val line = floatArrayOf(
        (Color.red(end) - Color.red(start)).toFloat(),
        (Color.green(end) - Color.green(start)).toFloat(),
        (Color.blue(end) - Color.blue(start)).toFloat()
    )
    val offset = floatArrayOf(
        (Color.red(color) - Color.red(start)).toFloat(),
        (Color.green(color) - Color.green(start)).toFloat(),
        (Color.blue(color) - Color.blue(start)).toFloat()
    )
    val lengthSquared = line.sumOf { (it * it).toDouble() }.toFloat()
    val t = if (lengthSquared == 0f) 0f
        else ((offset[0] * line[0] + offset[1] * line[1] + offset[2] * line[2]) / lengthSquared)
            .coerceIn(-extension, 1f + extension)
    val nearest = Color.rgb(
        (Color.red(start) + line[0] * t).toInt().coerceIn(0, 255),
        (Color.green(start) + line[1] * t).toInt().coerceIn(0, 255),
        (Color.blue(start) + line[2] * t).toInt().coerceIn(0, 255)
    )
    return t to colorDistance(color, nearest)
}

/** White, black or grey: low saturation, or too dark for its hue to read. */
internal fun isNeutral(color: Int): Boolean {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    return hsv[1] <= NEUTRAL_MAX_SATURATION || hsv[2] <= NEUTRAL_MAX_VALUE_FOR_DARK
}

private fun averageAround(bitmap: Bitmap, x: Int, y: Int, radius: Int = 2): Int {
    var r = 0; var g = 0; var b = 0; var count = 0
    for (py in max(0, y - radius)..min(bitmap.height - 1, y + radius)) {
        for (px in max(0, x - radius)..min(bitmap.width - 1, x + radius)) {
            val pixel = bitmap[px, py]
            if (Color.alpha(pixel) < 128) continue
            r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); count++
        }
    }
    if (count == 0) return Color.TRANSPARENT
    return Color.rgb(r / count, g / count, b / count)
}

/** The sample most like the others, so a stripe or accent does not tint a flat result. */
private fun medoidOf(colors: List<Int>): Int =
    colors.minBy { candidate -> colors.sumOf { colorDistance(candidate, it).toDouble() } }

private fun colorDistance(a: Int, b: Int): Float {
    val dr = (Color.red(a) - Color.red(b)).toFloat()
    val dg = (Color.green(a) - Color.green(b)).toFloat()
    val db = (Color.blue(a) - Color.blue(b)).toFloat()
    return sqrt(dr * dr + dg * dg + db * db) / (255f * sqrt(3f))
}
