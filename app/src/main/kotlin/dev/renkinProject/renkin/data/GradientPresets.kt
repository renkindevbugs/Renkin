package dev.renkinProject.renkin.data

import android.content.Context
import android.graphics.Color
import androidx.annotation.VisibleForTesting
import dev.renkinProject.renkin.icon.creator.MAX_GRADIENT_STOPS
import dev.renkinProject.renkin.icon.creator.MIN_GRADIENT_STOPS
import org.json.JSONArray

/** One ready-made gradient from the bundled library. Colours are ARGB, in paint order. */
data class GradientPreset(
    val name: String,
    val colors: List<Int>,
    // Derived once at parse time: the filter runs over all 377 entries on every keystroke, and
    // averaging hues per card is exactly the work not worth repeating there.
    val family: GradientFamily = gradientFamilyOf(colors)
)

/**
 * The rough looks a gradient can have. Hand-tagging 377 presets would rot the moment the library
 * is refreshed, so the group is derived from the colours themselves.
 */
enum class GradientFamily {
    WARM,
    COOL,
    PASTEL,
    DARK,
    MONO
}

/**
 * The gradient library shipped as an asset — no network for something that never changes, and the
 * Iconify browser stays the only feature that reaches out.
 */
object GradientPresets {

    private const val ASSET_NAME = "gradients.json"

    // Parsing 377 entries is cheap but pointless to repeat; the list is immutable for the process.
    @Volatile
    private var cached: List<GradientPreset>? = null

    fun load(context: Context): List<GradientPreset> = cached ?: synchronized(this) {
        cached ?: parse(
            runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        ).also { cached = it }
    }

    /** Tolerant on purpose: a broken asset reads as "no presets", never as a crash on open. */
    @VisibleForTesting
    fun parse(json: String): List<GradientPreset> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val name = entry.optString("name").takeIf { it.isNotBlank() } ?: continue
                val colors = entry.optJSONArray("colors") ?: continue
                val parsed = buildList {
                    for (stop in 0 until colors.length()) {
                        parseHexColor(colors.optString(stop))?.let(::add)
                    }
                }
                // The editor tops out at four stops; a trimmed preset would not be the gradient
                // its name promises, so those entries are simply left out.
                if (parsed.size in MIN_GRADIENT_STOPS..MAX_GRADIENT_STOPS) {
                    add(GradientPreset(name, parsed))
                }
            }
        }
        // The name is the grid's key: a refreshed library that ever ships the same name twice
        // would crash the list rather than show a duplicate.
        .distinctBy { it.name }
    }.getOrDefault(emptyList())
}

@VisibleForTesting
fun parseHexColor(value: String): Int? = runCatching {
    Color.parseColor(value.trim().takeIf { it.startsWith("#") } ?: return null)
}.getOrNull()

/**
 * The stop counts worth offering as chips: exactly the ones the library actually has, so no chip
 * ever leads to an empty grid.
 */
fun gradientStopCounts(presets: List<GradientPreset>): List<Int> =
    presets.map { it.colors.size }.distinct().sorted()

/**
 * Name search, one optional family and one optional stop count. All are applied to the same list
 * so the count on screen is always the number of cards below it.
 */
fun filterGradientPresets(
    presets: List<GradientPreset>,
    query: String,
    family: GradientFamily?,
    stops: Int? = null
): List<GradientPreset> {
    val trimmed = query.trim()
    return presets.filter { preset ->
        (family == null || preset.family == family) &&
            (stops == null || preset.colors.size == stops) &&
            (trimmed.isEmpty() || preset.name.contains(trimmed, ignoreCase = true))
    }
}

/**
 * Which group a gradient belongs to, decided on the average of its stops: near-grey is MONO, dark
 * beats saturation (a deep navy reads as dark, not cool), pale washed-out colours are PASTEL, and
 * the rest splits on hue — reds through yellows are warm, greens through violets are cool.
 */
fun gradientFamilyOf(colors: List<Int>): GradientFamily {
    if (colors.isEmpty()) return GradientFamily.MONO
    val hsv = FloatArray(3)
    var hueX = 0f
    var hueY = 0f
    var saturation = 0f
    var value = 0f
    colors.forEach { color ->
        Color.colorToHSV(color, hsv)
        // Hues are angles: averaging 350° and 10° arithmetically lands on cyan, so they are
        // averaged as unit vectors instead.
        val radians = Math.toRadians(hsv[0].toDouble())
        hueX += kotlin.math.cos(radians).toFloat()
        hueY += kotlin.math.sin(radians).toFloat()
        saturation += hsv[1]
        value += hsv[2]
    }
    val count = colors.size
    saturation /= count
    value /= count
    val hue = ((Math.toDegrees(kotlin.math.atan2(hueY, hueX).toDouble()).toFloat() % 360f) + 360f) %
        360f

    return when {
        saturation < MONO_SATURATION -> GradientFamily.MONO
        value < DARK_VALUE -> GradientFamily.DARK
        saturation < PASTEL_SATURATION && value > PASTEL_VALUE -> GradientFamily.PASTEL
        hue < WARM_HUE_END || hue >= WARM_HUE_START -> GradientFamily.WARM
        else -> GradientFamily.COOL
    }
}

private const val MONO_SATURATION = 0.12f
private const val DARK_VALUE = 0.42f
private const val PASTEL_SATURATION = 0.45f
private const val PASTEL_VALUE = 0.8f
// Warm wraps around red: yellow-green up to 65°, and magenta-red from 320°.
private const val WARM_HUE_END = 65f
private const val WARM_HUE_START = 320f
