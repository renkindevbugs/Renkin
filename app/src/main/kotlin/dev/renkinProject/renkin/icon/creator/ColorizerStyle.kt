package dev.renkinProject.renkin.icon.creator

/**
 * Reusable colourizer configuration shared by full-icon colourizing today and selective
 * colourizing/presets later. Colours are ARGB values; gradient angles run clockwise in screen
 * coordinates (0° points up and values increase clockwise).
 */
data class ColorizerStyle(
    val mode: ColorizerMode = ColorizerMode.SINGLE_COLOR,
    val gradientType: GradientType = GradientType.LINEAR,
    val firstColor: Int,
    /**
     * Gradient stops after [firstColor]. The first colour doubles as the single-colour value, so
     * it stays a separate field instead of living in this list.
     */
    val gradientStops: List<Int> = listOf(android.graphics.Color.BLACK),
    /**
     * Where each stop of [allGradientColors] sits, 0..1. Empty means "spread evenly", which is
     * exactly what every gradient made before positions existed did — so old styles, old presets
     * and old backups keep rendering the same.
     */
    val gradientPositions: List<Float> = emptyList(),
    val gradientAngle: Float = 0f,
    val flat: Boolean = false,
    val monochrome: Boolean = false,
    val inverse: Boolean = false
) {
    /**
     * Every stop in paint order. Alpha is kept: a translucent stop lets the artwork show through,
     * which is the point of tinting with a gradient.
     */
    val allGradientColors: List<Int>
        get() = listOf(firstColor) + gradientStops
}

/** Two stops make a gradient; past four they smear into mud at launcher icon sizes. */
const val MIN_GRADIENT_STOPS = 2
const val MAX_GRADIENT_STOPS = 4

enum class ColorizerMode {
    SINGLE_COLOR,
    GRADIENT
}

enum class GradientType {
    LINEAR,
    RADIAL
}

fun normalizeGradientAngle(angle: Float): Float = angle.coerceIn(0f, 360f)

/** The even spread a positionless gradient already paints, as explicit values to drag from. */
fun evenGradientPositions(count: Int): List<Float> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(0f)
    else -> List(count) { it / (count - 1).toFloat() }
}

/**
 * CSS gradient semantics: a stop that would sit before the one in front of it is pulled up to it,
 * which paints a hard edge instead of re-ordering the list. Dragging a handle past its neighbour
 * therefore never shuffles the stops — the same thing every gradient tool does, and what the
 * shaders demand anyway (a descending list is rejected).
 */
fun clampedGradientPositions(positions: List<Float>): List<Float> {
    var previous = 0f
    return positions.map { position ->
        position.coerceIn(previous, 1f).also { previous = it }
    }
}

/**
 * The colour the gradient already paints at [fraction], mixed the way the shader mixes it (per
 * channel, alpha included). Dropping a new stop uses this, so adding one never changes what the
 * gradient looks like until its colour is picked.
 */
fun gradientColorAt(colors: List<Int>, positions: List<Float>, fraction: Float): Int {
    if (colors.isEmpty()) return android.graphics.Color.BLACK
    val stops = if (positions.size == colors.size) {
        clampedGradientPositions(positions)
    } else {
        evenGradientPositions(colors.size)
    }
    val next = stops.indexOfFirst { it >= fraction }
    if (next <= 0) return colors[if (next < 0) colors.lastIndex else 0]
    val span = stops[next] - stops[next - 1]
    // A zero span is a hard edge; the later stop owns the boundary, same as CSS.
    val ratio = if (span <= 0f) 1f else ((fraction - stops[next - 1]) / span).coerceIn(0f, 1f)
    return blendArgb(colors[next - 1], colors[next], ratio)
}

private fun blendArgb(from: Int, to: Int, ratio: Float): Int {
    fun channel(shift: Int): Int {
        val start = (from shr shift) and 0xFF
        val end = (to shr shift) and 0xFF
        return (start + (end - start) * ratio).toInt().coerceIn(0, 255) shl shift
    }
    return channel(24) or channel(16) or channel(8) or channel(0)
}

/** Positions to hand a Shader, or null to let it spread the stops evenly. */
fun shaderGradientPositions(colors: List<Int>, positions: List<Float>): FloatArray? {
    if (positions.size != colors.size || colors.size < MIN_GRADIENT_STOPS) return null
    return clampedGradientPositions(positions).toFloatArray()
}

/** Half-width of the magnet zone around each 45° multiple, in degrees. */
const val GRADIENT_ANGLE_MAGNET = 5f

/**
 * Dial input stays free at 1° precision, but a fingertip almost never lands exactly on the
 * cardinal/diagonal angles people actually aim for, so pull those in when close enough.
 */
fun snapGradientAngle(angle: Float): Float {
    val raw = (angle % 360f + 360f) % 360f
    val nearest = Math.round(raw / 45f) * 45f
    return if (kotlin.math.abs(raw - nearest) <= GRADIENT_ANGLE_MAGNET) nearest % 360f else raw
}
