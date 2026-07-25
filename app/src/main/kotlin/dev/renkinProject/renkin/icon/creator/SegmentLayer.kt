package dev.renkinProject.renkin.icon.creator

/**
 * One "colourize these regions like this" step. Layers apply in order, so an icon can carry a
 * blue background and a yellow glyph without the second pick undoing the first.
 */
data class SegmentLayer(
    val targets: List<Int>,
    val tolerance: Float = SEGMENT_TOLERANCE_DEFAULT,
    val style: ColorizerStyle
)

// Saved-state and preference encoding. Layers are colours plus a handful of scalars, so a flat
// string beats pulling in a serialiser — and it survives a process death in a Bundle.
private const val FIELD = ";"
private const val LIST = ","

fun SegmentLayer.encode(): String = listOf(
    targets.joinToString(LIST),
    tolerance,
    style.mode.ordinal,
    style.gradientType.ordinal,
    style.firstColor,
    style.gradientStops.joinToString(LIST),
    style.gradientAngle,
    style.flat,
    style.monochrome,
    style.inverse
).joinToString(FIELD)

fun decodeSegmentLayer(encoded: String): SegmentLayer? {
    val parts = encoded.split(FIELD)
    if (parts.size < 10) return null
    return runCatching {
        SegmentLayer(
            targets = parts[0].split(LIST).mapNotNull { it.toIntOrNull() },
            tolerance = parts[1].toFloat(),
            style = ColorizerStyle(
                mode = ColorizerMode.entries[parts[2].toInt()],
                gradientType = GradientType.entries[parts[3].toInt()],
                firstColor = parts[4].toInt(),
                gradientStops = parts[5].split(LIST).mapNotNull { it.toIntOrNull() }
                    .ifEmpty { listOf(android.graphics.Color.BLACK) },
                gradientAngle = parts[6].toFloat(),
                flat = parts[7].toBooleanStrict(),
                monochrome = parts[8].toBooleanStrict(),
                inverse = parts[9].toBooleanStrict()
            )
        )
    }.getOrNull()
}
