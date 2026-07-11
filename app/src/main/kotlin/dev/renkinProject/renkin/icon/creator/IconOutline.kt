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

    // How different two neighbouring pixels may be (max per-channel delta) and still count as
    // the same outline. Antialiased fringes and gradients step gently and pass; the outline
    // meeting the icon's fill is a hard jump and stops the flood.
    private const val LOCAL_TOLERANCE = 40

    /**
     * Repaints the icon's EXISTING outline instead of adding one. The outline is found by an
     * edge-stopping flood: it grows inward from the transparency boundary while neighbouring
     * pixels stay colour-similar (so antialiased fringes AND gradient outlines are covered in
     * full), and stops at the sharp colour jump where the outline meets the icon's fill —
     * [widthPx] only caps the depth as a safety net. The repaint transfers [color]'s hue and
     * saturation but scales each pixel's own brightness relative to the outline's core, so
     * soft edges and gradients keep their shading in the new colour instead of flattening.
     */
    private fun recolorOutline(src: Bitmap, widthPx: Float, color: Int): Bitmap {
        val w = src.width
        val h = src.height
        val maxDepth = max(1, widthPx.roundToInt())
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Seed: visible pixels touching transparency (or the bitmap edge).
        val depth = IntArray(w * h) { -1 }
        val queue = ArrayDeque<Int>()
        fun transparentAt(x: Int, y: Int): Boolean =
            x < 0 || y < 0 || x >= w || y >= h || (pixels[y * w + x] ushr 24) == 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if ((pixels[i] ushr 24) == 0) continue
                if (transparentAt(x - 1, y) || transparentAt(x + 1, y) ||
                    transparentAt(x, y - 1) || transparentAt(x, y + 1)
                ) {
                    depth[i] = 0
                    queue.add(i)
                }
            }
        }

        // Edge-stopping flood: grow to visible 4-neighbours of similar colour, up to maxDepth.
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (depth[i] >= maxDepth) continue
            val x = i % w
            val y = i / w
            for (n in intArrayOf(
                if (x > 0) i - 1 else -1,
                if (x < w - 1) i + 1 else -1,
                if (y > 0) i - w else -1,
                if (y < h - 1) i + w else -1
            )) {
                if (n < 0 || depth[n] >= 0) continue
                if ((pixels[n] ushr 24) == 0) continue
                if (!similar(pixels[i], pixels[n])) continue
                depth[n] = depth[i] + 1
                queue.add(n)
            }
        }

        // Brightness reference: the brightest value among the outline's CORE pixels (past the
        // antialiased first layers), so the fringe scales below it instead of clipping.
        val hsv = FloatArray(3)
        var refValue = 0f
        for (i in pixels.indices) {
            if (depth[i] >= 2 || (depth[i] >= 0 && maxDepth < 2)) {
                Color.colorToHSV(pixels[i], hsv)
                if (hsv[2] > refValue) refValue = hsv[2]
            }
        }
        if (refValue <= 0f) refValue = 1f

        val target = FloatArray(3)
        Color.colorToHSV(color, target)
        for (i in pixels.indices) {
            if (depth[i] < 0) continue
            Color.colorToHSV(pixels[i], hsv)
            val out = floatArrayOf(target[0], target[1], (target[2] * hsv[2] / refValue).coerceIn(0f, 1f))
            pixels[i] = (pixels[i] and 0xFF000000.toInt()) or (Color.HSVToColor(out) and 0x00FFFFFF)
        }

        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return outBitmap
    }

    /**
     * Undoes the outline step inside [mask]'s painted areas: wherever the mask is opaque, the
     * outlined result is replaced by the [original] pixels — the user's eraser says "no
     * outline here", it never punches holes into the icon itself.
     */
    fun eraseOutline(outlined: Bitmap, original: Bitmap, mask: Bitmap): Bitmap {
        val w = outlined.width
        val h = outlined.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(outlined, 0f, 0f, null)

        // Clear the masked region, then patch the original back into it.
        val clear = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        clear.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(mask, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), clear)

        val patch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val patchCanvas = Canvas(patch)
        patchCanvas.drawBitmap(mask, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        val srcIn = Paint(Paint.FILTER_BITMAP_FLAG)
        srcIn.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        patchCanvas.drawBitmap(original, 0f, 0f, srcIn)
        canvas.drawBitmap(patch, 0f, 0f, null)

        return out
    }

    private fun similar(a: Int, b: Int): Boolean =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= LOCAL_TOLERANCE &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) <= LOCAL_TOLERANCE &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= LOCAL_TOLERANCE
}
