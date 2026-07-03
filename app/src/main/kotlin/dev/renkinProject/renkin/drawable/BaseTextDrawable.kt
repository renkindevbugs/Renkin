package dev.renkinProject.renkin.drawable

import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable

// Shared base for the text icon sources. Holds the [paint] (a TextPaint in the multi-line case)
// and the paint-backed operations both subclasses need; draw / getPaths stay subclass-specific.
abstract class BaseTextDrawable: Drawable() {
    protected abstract val paint: Paint

    abstract fun getPaths(): List<Path>

    /** Rounded width of [text] under the current paint — used to fit/size the text. */
    protected fun textWidth(text: CharSequence): Int =
        (paint.measureText(text, 0, text.length) + 0.5).toInt()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        return paint.alpha
    }
}
