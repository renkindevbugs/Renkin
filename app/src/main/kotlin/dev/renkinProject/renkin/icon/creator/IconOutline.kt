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

    // DEBUG (remove before merge): when true, RECOLOR paints the flood SELECTION opaque
    // magenta instead of running the HSV transfer, so a single device screenshot shows
    // whether the wrong pixels are selected or the right pixels are painted wrong.
    private const val DEBUG_TINT_SELECTION = true

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

        if (DEBUG_TINT_SELECTION) {
            for (i in pixels.indices) {
                if (depth[i] < 0) continue
                pixels[i] = (pixels[i] and 0xFF000000.toInt()) or 0x00FF00FF
            }
            val debugBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            debugBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            return debugBitmap
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
     * Undoes the outline step inside [mask]'s painted areas: per pixel, the result blends
     * from the outlined image towards [original] by the mask's alpha. Done in software on
     * unpremultiplied pixels — a clear-then-patch canvas composite left visible seams along
     * the mask's antialiased edges wherever it crossed the icon's fill (where outlined and
     * original are the SAME colour, the blend must be an exact no-op).
     */
    fun eraseOutline(outlined: Bitmap, original: Bitmap, mask: Bitmap): Bitmap {
        val w = outlined.width
        val h = outlined.height
        val scaledMask = if (mask.width == w && mask.height == h) mask
            else Bitmap.createScaledBitmap(mask, w, h, true)

        val outPx = IntArray(w * h)
        val origPx = IntArray(w * h)
        val maskPx = IntArray(w * h)
        outlined.getPixels(outPx, 0, w, 0, 0, w, h)
        original.getPixels(origPx, 0, w, 0, 0, w, h)
        scaledMask.getPixels(maskPx, 0, w, 0, 0, w, h)

        for (i in outPx.indices) {
            val m = maskPx[i] ushr 24
            if (m == 0) continue
            if (m == 255) {
                outPx[i] = origPx[i]
                continue
            }
            // Alpha-weighted blend of unpremultiplied colours, so a transparent side doesn't
            // drag the channels towards black along the mask's soft edge.
            val aOut = outPx[i] ushr 24
            val aOrig = origPx[i] ushr 24
            val wOut = aOut * (255 - m)
            val wOrig = aOrig * m
            val aSum = wOut + wOrig
            val alpha = (aOut * (255 - m) + aOrig * m) / 255
            outPx[i] = if (aSum == 0) 0 else {
                val r = ((outPx[i] shr 16 and 0xFF) * wOut + (origPx[i] shr 16 and 0xFF) * wOrig) / aSum
                val g = ((outPx[i] shr 8 and 0xFF) * wOut + (origPx[i] shr 8 and 0xFF) * wOrig) / aSum
                val b = ((outPx[i] and 0xFF) * wOut + (origPx[i] and 0xFF) * wOrig) / aSum
                (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    private fun similar(a: Int, b: Int): Boolean =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= LOCAL_TOLERANCE &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) <= LOCAL_TOLERANCE &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= LOCAL_TOLERANCE
}
