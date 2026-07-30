package dev.renkinProject.renkin.icon.creator

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.drawable.MultiLineTextDrawable
import dev.renkinProject.renkin.drawable.TextDrawable

// Unicode separators matter too: Android labels can contain non-breaking and full-width spaces.
private val WORD_SEPARATOR = Regex("""[\s\p{Z}]+""")

/** Takes whole Unicode code points so an emoji is never split into an invalid surrogate. */
private fun String.takeCodePoints(count: Int): String {
    if (count <= 0 || isEmpty()) return ""
    val actualCount = codePointCount(0, length).coerceAtMost(count)
    return substring(0, offsetByCodePoints(0, actualCount))
}

/**
 * The two characters a "Two letters" icon shows: the initials of the first two words, or the
 * first two characters of a single word. Splitting on a whitespace RUN matters — "Foo  Bar" used
 * to yield an empty second token and crash on its first character.
 */
internal fun twoLetterInitials(appName: String): String {
    val words = appName.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> words[0].takeCodePoints(1) + words[1].takeCodePoints(1)
        words.isEmpty() -> ""
        else -> words[0].takeCodePoints(2)
    }
}

class LetterGenerator(ctx: Context, fontPath: String = "") {
    // The bundled Arcticons Sans by default; a picked system font replaces it. A stale path
    // (font removed by an OS update) falls back instead of crashing generation.
    private val font = fontPath.takeIf { it.isNotEmpty() }
        ?.let { FontCatalog.typeface(it) }
        ?: ResourcesCompat.getFont(ctx, R.font.arcticonssans_regular)!!

    fun generateFirstLetter(appName: String, color: Int, strokeWidth: Float, maxSize: Int): Drawable {
        var text = appName.trim()
        if (text.isNotEmpty()) {
            text = text.substring(0, 1)
        }

        return TextDrawable(text, font, 200F, color, strokeWidth, maxSize, maxSize)
    }

    fun generateTwoLetters(appName: String, color: Int, strokeWidth: Float, maxSize: Int): Drawable =
        TextDrawable(twoLetterInitials(appName), font, 150F, color, strokeWidth, maxSize, maxSize)

    fun generateAppName(appName: String, color: Int, maxSize: Int): Drawable {
        return MultiLineTextDrawable(appName, font, 50F, 30F, color, maxSize, 3, maxSize)
    }

    /** Renders [text] exactly as given, monogram-sized — the Custom text type. */
    fun generateExact(text: String, color: Int, strokeWidth: Float, maxSize: Int): Drawable {
        val fontSize = if (text.length <= 1) 200F else 150F
        return TextDrawable(text, font, fontSize, color, strokeWidth, maxSize, maxSize)
    }
}
