package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

class LetterGenerator(private val ctx: Context) {
    fun generateFirstLetter(appName: String): Drawable {
        val resID = getResIDOfLetter(appName[0])
        return ContextCompat.getDrawable(ctx, resID)!!
    }

    private fun getResIDOfLetter(letter: Char): Int {
        return when (letter) {
            '0' -> R.drawable.arcticons_font
            '1' -> R.drawable.arcticons_font
            '2' -> R.drawable.arcticons_font
            '3' -> R.drawable.arcticons_font
            '4' -> R.drawable.arcticons_font
            '5' -> R.drawable.arcticons_font
            '6' -> R.drawable.arcticons_font
            '7' -> R.drawable.arcticons_font
            '8' -> R.drawable.arcticons_font
            '9' -> R.drawable.arcticons_font
            'A' -> R.drawable.arcticons_font
            'B' -> R.drawable.arcticons_font
            'C' -> R.drawable.arcticons_font
            'D' -> R.drawable.arcticons_font
            'E' -> R.drawable.arcticons_font
            'F' -> R.drawable.arcticons_font
            'G' -> R.drawable.arcticons_font
            'H' -> R.drawable.arcticons_font
            'I' -> R.drawable.arcticons_font
            'J' -> R.drawable.arcticons_font
            'K' -> R.drawable.arcticons_font
            'L' -> R.drawable.arcticons_font
            'M' -> R.drawable.arcticons_font
            'N' -> R.drawable.arcticons_font
            'O' -> R.drawable.arcticons_font
            'P' -> R.drawable.arcticons_font
            'Q' -> R.drawable.arcticons_font
            'R' -> R.drawable.arcticons_font
            'S' -> R.drawable.arcticons_font
            'T' -> R.drawable.arcticons_font
            'U' -> R.drawable.arcticons_font
            'V' -> R.drawable.arcticons_font
            'W' -> R.drawable.arcticons_font
            'X' -> R.drawable.arcticons_font
            'Y' -> R.drawable.arcticons_font
            'Z' -> R.drawable.arcticons_font
            else -> 0
        }
    }
}