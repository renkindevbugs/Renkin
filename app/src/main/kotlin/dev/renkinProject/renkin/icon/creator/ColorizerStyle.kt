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
