package com.kaanelloed.iconeration.drawable

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

interface IconPackDrawable {
    @Composable
    fun getPainter(): Painter

    fun toBitmap(): Bitmap

    fun toDbString(): String
}