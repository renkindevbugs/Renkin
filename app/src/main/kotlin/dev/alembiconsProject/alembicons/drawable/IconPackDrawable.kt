package dev.alembiconsProject.alembicons.drawable

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

abstract class IconPackDrawable: Drawable() {
    @Composable
    abstract fun getPainter(): Painter

    abstract fun toBitmap(): Bitmap

    abstract fun toDbString(): String

    open fun isAdaptiveIcon(): Boolean {
        return false
    }
}