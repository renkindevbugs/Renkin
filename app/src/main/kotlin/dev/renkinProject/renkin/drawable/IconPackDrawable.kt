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
     * Rasterises an already-modified icon without normalising its geometry again. Bitmap-backed
     * icons already carry their final canvas; vectors override this to preserve baked position.
     */
    open fun toModifierBitmap(size: Int = 256): Bitmap = toBitmap()

    /**
     * What [getPainter] would draw, as a bitmap. Lists use this instead of the painter: a vector
     * painter sizes itself from its own layer, so a freshly assigned icon visibly grew into place
     * on the first frames. Rasterising to the row's size makes it appear already correct.
     */
    open fun previewBitmap(): Bitmap = toBitmap()

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
