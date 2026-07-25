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

fun SegmentLayer.encode(): String =
    listOf(targets.joinToString(LIST), tolerance).joinToString(FIELD) +
        FIELD + encodeColorizerStyle(style)

/** The style's own fields, reused by saved colour presets. */
fun encodeColorizerStyle(style: ColorizerStyle): String = listOf(
    style.mode.ordinal,
    style.gradientType.ordinal,
    style.firstColor,
    style.gradientStops.joinToString(LIST),
    style.gradientAngle,
    style.flat,
    style.monochrome,
    style.inverse
).joinToString(FIELD)

fun decodeColorizerStyle(encoded: String): ColorizerStyle? =
    decodeStyleFields(encoded.split(FIELD), 0)

private fun decodeStyleFields(parts: List<String>, offset: Int): ColorizerStyle? {
    if (parts.size < offset + 8) return null
    return runCatching {
        ColorizerStyle(
            mode = ColorizerMode.entries[parts[offset].toInt()],
            gradientType = GradientType.entries[parts[offset + 1].toInt()],
            firstColor = parts[offset + 2].toInt(),
            gradientStops = parts[offset + 3].split(LIST).mapNotNull { it.toIntOrNull() }
                .ifEmpty { listOf(android.graphics.Color.BLACK) },
            gradientAngle = parts[offset + 4].toFloat(),
            flat = parts[offset + 5].toBooleanStrict(),
            monochrome = parts[offset + 6].toBooleanStrict(),
            inverse = parts[offset + 7].toBooleanStrict()
        )
    }.getOrNull()
}

fun decodeSegmentLayer(encoded: String): SegmentLayer? {
    val parts = encoded.split(FIELD)
    val style = decodeStyleFields(parts, 2) ?: return null
    return runCatching {
        SegmentLayer(
            targets = parts[0].split(LIST).mapNotNull { it.toIntOrNull() },
            tolerance = parts[1].toFloat(),
            style = style
        )
    }.getOrNull()
}
