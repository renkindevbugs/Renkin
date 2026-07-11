package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** The Modifier tab's outline step: none, add a contour, or repaint the existing one. */
enum class OutlineMode { NONE, ADD, RECOLOR }

object IconOutline {

    fun apply(src: Bitmap, mode: OutlineMode, widthPx: Float, color: Int): Bitmap = when (mode) {
        OutlineMode.NONE -> src
        OutlineMode.ADD -> addOutline(src, widthPx, color)
        OutlineMode.RECOLOR -> recolorOutline(src, widthPx, color)
    }

    /**
     * Draws a contour of [color] hugging the icon's silhouette: the icon's alpha mask is
     * stamped in a ring of offsets around every direction (a cheap dilation), then the icon
     * itself is drawn back on top — the colour stays visible only in the [widthPx] band
     * outside the original edges.
     */
    private fun addOutline(src: Bitmap, widthPx: Float, color: Int): Bitmap {
        val width = max(1f, widthPx)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val mask = src.extractAlpha()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = color

        // Two rings of stamped masks approximate a filled dilation disc; the inner area is
        // covered by the icon drawn on top anyway.
        for (radius in floatArrayOf(width, width / 2f)) {
            val steps = 16
            for (i in 0 until steps) {
                val angle = i * (2 * Math.PI / steps)
                canvas.drawBitmap(
                    mask,
                    (radius * cos(angle)).toFloat(),
                    (radius * sin(angle)).toFloat(),
                    paint
                )
            }
        }
        mask.recycle()

        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Repaints the icon's EXISTING outline instead of adding one: every visible pixel within
     * [widthPx] of transparency (found by eroding the alpha mask that many times) keeps its
     * alpha but takes [color]'s hue. An icon that already carries a ring around its edge —
     * the reason this mode exists — is recoloured without growing thicker; interior strokes
     * away from the silhouette boundary are deliberately left alone.
     */
    private fun recolorOutline(src: Bitmap, widthPx: Float, color: Int): Bitmap {
        val w = src.width
        val h = src.height
        val iterations = max(1, widthPx.roundToInt())
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // opaque[i] shrinks by one 4-neighbour ring per iteration; whatever it loses is the
        // boundary band to recolour.
        var opaque = BooleanArray(w * h) { (pixels[it] ushr 24) > 0 }
        val band = BooleanArray(w * h)
        repeat(iterations) {
            val eroded = BooleanArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!opaque[i]) continue
                    val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                        !opaque[i - 1] || !opaque[i + 1] || !opaque[i - w] || !opaque[i + w]
                    if (edge) band[i] = true else eroded[i] = true
                }
            }
            opaque = eroded
        }

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        for (i in pixels.indices) {
            if (band[i]) {
                pixels[i] = (pixels[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
