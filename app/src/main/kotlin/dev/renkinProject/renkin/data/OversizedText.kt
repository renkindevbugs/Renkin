package dev.renkinProject.renkin.data

/**
 * Android reads query results through a CursorWindow that holds about 2 MB per row. A stored icon
 * can be larger (detailed adaptive layers are kept twice, as rendered and base artwork), and a
 * row that does not fit throws SQLiteBlobTooBigException on every read (issue #19). Such values
 * are therefore left out of row queries and read separately in chunks that always fit.
 */
internal object OversizedText {
    // Longer values stay out of a row: the two artwork columns plus every bounded column of an
    // icon row then remain well below one window.
    const val INLINE_CHARS = 256 * 1024
    const val CHUNK_CHARS = 512 * 1024

    /** Reassembles a value of [length] characters from 1-based [chunk] reads (SQL `substr`). */
    fun read(length: Int, chunk: (start: Int, count: Int) -> String): String = buildString(length) {
        var start = 1
        while (start <= length) {
            val part = chunk(start, CHUNK_CHARS)
            // A value replaced between two reads must fail loudly, never be stitched together.
            check(part.isNotEmpty()) { "Stored value changed while it was read" }
            append(part)
            start += part.length
        }
        check(this.length == length) { "Stored value changed while it was read" }
    }
}

// Artwork columns read inline only when they fit; longer ones come back empty and are read in
// chunks (see OversizedText and RenkinPackRepository).
private const val INLINE_DRAWABLE =
    "CASE WHEN length(drawable) > ${OversizedText.INLINE_CHARS} THEN '' ELSE drawable END AS drawable"
private const val INLINE_BASE_DRAWABLE =
    "CASE WHEN length(baseDrawable) > ${OversizedText.INLINE_CHARS} THEN '' ELSE baseDrawable END AS baseDrawable"

internal const val ICON_ROW_COLUMNS = "packageName, activityName, isAdaptiveIcon, isXml, $INLINE_DRAWABLE, " +
    "calendarEnabled, calendarPrefix, calendarPackName, sourcePackName, profileId, sourceDrawableName, " +
    "isCustomIcon, isLegacyIcon, $INLINE_BASE_DRAWABLE, baseIsAdaptiveIcon, baseIsXml, sourceUrl, " +
    "isFallbackIcon, modifierRecipe, modifierInput"

internal const val FINGERPRINT_COLUMNS = "packageName, activityName, $INLINE_DRAWABLE, calendarEnabled, calendarPrefix"

internal const val OVERSIZED_ARTWORK_COLUMNS =
    "profileId, packageName, activityName, length(drawable) AS drawableChars, length(baseDrawable) AS baseDrawableChars"

internal const val OVERSIZED_ARTWORK_FILTER =
    "(length(drawable) > ${OversizedText.INLINE_CHARS} OR length(baseDrawable) > ${OversizedText.INLINE_CHARS})"

/** An icon row whose artwork was left out of a row query because it is too long to fit. */
data class OversizedIconArtwork(
    val profileId: Long,
    val packageName: String,
    val activityName: String,
    val drawableChars: Int,
    val baseDrawableChars: Int
)
