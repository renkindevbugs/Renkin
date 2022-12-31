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
            '0' -> R.drawable.arcticons_font_0
            '1' -> R.drawable.arcticons_font_1
            '2' -> R.drawable.arcticons_font_2
            '3' -> R.drawable.arcticons_font_3
            '4' -> R.drawable.arcticons_font_4
            '5' -> R.drawable.arcticons_font_5
            '6' -> R.drawable.arcticons_font_6
            '7' -> R.drawable.arcticons_font_7
            '8' -> R.drawable.arcticons_font_8
            '9' -> R.drawable.arcticons_font_9
            'A' -> R.drawable.arcticons_font_a
            'B' -> R.drawable.arcticons_font_b
            'C' -> R.drawable.arcticons_font_c
            'D' -> R.drawable.arcticons_font_d
            'E' -> R.drawable.arcticons_font_e
            'F' -> R.drawable.arcticons_font_f
            'G' -> R.drawable.arcticons_font_g
            'H' -> R.drawable.arcticons_font_h
            'I' -> R.drawable.arcticons_font_i
            'J' -> R.drawable.arcticons_font_j
            'K' -> R.drawable.arcticons_font_k
            'L' -> R.drawable.arcticons_font_l
            'M' -> R.drawable.arcticons_font_m
            'N' -> R.drawable.arcticons_font_n
            'O' -> R.drawable.arcticons_font_o
            'P' -> R.drawable.arcticons_font_p
            'Q' -> R.drawable.arcticons_font_q
            'R' -> R.drawable.arcticons_font_r
            'S' -> R.drawable.arcticons_font_s
            'T' -> R.drawable.arcticons_font_t
            'U' -> R.drawable.arcticons_font_u
            'V' -> R.drawable.arcticons_font_v
            'W' -> R.drawable.arcticons_font_w
            'X' -> R.drawable.arcticons_font_x
            'Y' -> R.drawable.arcticons_font_y
            'Z' -> R.drawable.arcticons_font_z
            else -> R.drawable.arcticons_font_qm
        }
    }
}