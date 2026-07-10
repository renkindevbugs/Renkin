package dev.renkinProject.renkin.icon.creator

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.drawable.MultiLineTextDrawable
import dev.renkinProject.renkin.drawable.TextDrawable

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

    fun generateTwoLetters(appName: String, color: Int, strokeWidth: Float, maxSize: Int): Drawable {
        var text = appName.trim()
        text = if (text.contains(" ")) {
            val words = text.split(" ")
            words[0][0].toString() + words[1][0]
        } else {
            if (text.length > 2) text.substring(0, 2) else text
        }

        return TextDrawable(text, font, 150F, color, strokeWidth, maxSize, maxSize)
    }

    fun generateAppName(appName: String, color: Int, maxSize: Int): Drawable {
        return MultiLineTextDrawable(appName, font, 50F, 30F, color, maxSize, 3, maxSize)
    }

    /** Renders [text] exactly as given, monogram-sized — the Custom text type. */
    fun generateExact(text: String, color: Int, strokeWidth: Float, maxSize: Int): Drawable {
        val fontSize = if (text.length <= 1) 200F else 150F
        return TextDrawable(text, font, fontSize, color, strokeWidth, maxSize, maxSize)
    }
}