package dev.renkinProject.renkin.feature.launch

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/** Must equal `@color/launch_plum`: the overlay's first frame continues the launch window. */
internal val LaunchPlum = Color(0xFF1E1228)

/** Must equal `@color/launch_gold`: the static symbol the launch window already shows. */
internal val LaunchGold = Color(0xFFD5B15B)

internal const val LAUNCH_FIRE_DURATION_MS = 1200f

/**
 * The launch animation as pure functions of progress `p = elapsed / duration`. Ported 1:1 from
 * `reports/fire-splash/fire-splash-mockup.html` (spike ignition, copper-to-gold finish);
 * the symbol starts as the splash icon's static gold symbol instead of a charcoal silhouette.
 */
internal object LaunchFireTimeline {
    fun front(p: Float): Float = mix(-0.08f, 1.12f, smoothStep(0.06f, 0.62f, p))
    fun flame(p: Float): Float = smoothStep(0.03f, 0.22f, p) * (1f - smoothStep(0.52f, 0.82f, p))
    fun glow(p: Float): Float = smoothStep(0.05f, 0.4f, p) * (1f - smoothStep(0.6f, 0.95f, p))
    fun sheen(p: Float): Float = mix(-0.4f, 1.4f, smoothStep(0.72f, 0.96f, p))
    /** 0 = splash-icon geometry (seamless handoff), 1 = full launch size, with a small overshoot. */
    fun grow(p: Float): Float {
        val t = smoothStep(0.04f, 0.6f, p)
        return t * (1f + 0.06f * sin(t * PI.toFloat()))
    }
}

internal fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

internal fun mix(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

internal fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    val t = clamp01((value - edge0) / (edge1 - edge0))
    return t * t * (3f - 2f * t)
}

/** Piecewise-linear colour ramp; stops are (position, r, g, b) quadruples with ascending positions. */
internal class ColorStops(vararg values: Float) {
    private val stops = values

    fun red(u: Float) = channel(u, 1)
    fun green(u: Float) = channel(u, 2)
    fun blue(u: Float) = channel(u, 3)

    private fun channel(u: Float, offset: Int): Float {
        var i = 4
        while (i < stops.size) {
            if (u <= stops[i]) {
                val start = stops[i - 4]
                val fraction = (u - start) / (stops[i] - start)
                return mix(stops[i - 4 + offset], stops[i + offset], fraction)
            }
            i += 4
        }
        return stops[stops.size - 4 + offset]
    }
}

/** White-hot metal cooling through yellow and orange. */
internal val HotMetal = ColorStops(
    0f, 255f, 248f, 225f,
    0.18f, 255f, 196f, 92f,
    0.45f, 244f, 140f, 52f,
    0.75f, 226f, 160f, 72f,
    1f, 212f, 176f, 90f
)

/** Resting finish: copper bottom-left transmuted to pale gold top-right. */
internal val CopperToGold = ColorStops(
    0f, 200f, 80f, 40f,
    0.5f, 218f, 150f, 60f,
    1f, 255f, 232f, 160f
)
