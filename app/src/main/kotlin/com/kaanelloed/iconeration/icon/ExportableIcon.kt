package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

abstract class ExportableIcon(val exportAsAdaptiveIcon: Boolean): BaseIcon() {
    @Composable
    abstract fun getPainter(): Painter

    abstract fun toBitmap(): Bitmap

    abstract fun toDbString(): String
}