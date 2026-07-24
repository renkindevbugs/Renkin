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
    val secondColor: Int = android.graphics.Color.BLACK,
    val gradientAngle: Float = 0f,
    val flat: Boolean = false,
    val monochrome: Boolean = false,
    val inverse: Boolean = false
)

enum class ColorizerMode {
    SINGLE_COLOR,
    GRADIENT
}

enum class GradientType {
    LINEAR,
    RADIAL
}

fun normalizeGradientAngle(angle: Float): Float = angle.coerceIn(0f, 360f)
