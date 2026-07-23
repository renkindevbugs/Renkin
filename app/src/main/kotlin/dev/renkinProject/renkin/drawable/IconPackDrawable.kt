package dev.renkinProject.renkin.drawable

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

abstract class IconPackDrawable: Drawable() {
    @Composable
    abstract fun getPainter(): Painter

    abstract fun toBitmap(): Bitmap

    /**
     * A bitmap rasterised specifically for the icon browser. Thin vector strokes survive
     * better when they are drawn at the target size than when a large finished raster is
     * downscaled. Most icons can reuse their regular bitmap.
     */
    open fun toBrowserPreviewBitmap(): Bitmap = toBitmap()

    abstract fun toDbString(): String

    open fun isAdaptiveIcon(): Boolean {
        return false
    }
}
