package dev.alembiconsProject.alembicons.icon.creator

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.drawable.MultiLineTextDrawable
import dev.alembiconsProject.alembicons.drawable.TextDrawable

class LetterGenerator(ctx: Context) {
    private val font = ResourcesCompat.getFont(ctx, R.font.arcticonssans_regular)!!

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
}