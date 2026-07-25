package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The Modifier tab's outline step: none, add a contour, or repaint the existing one. */
enum class OutlineMode { NONE, ADD, RECOLOR }

object IconOutline {

    /**
     * [style] carries the outline's colour: a gradient paints the contour with a shader (ADD) or
     * with the gradient's colour at each repainted pixel (RECOLOR). Null or single-colour styles
     * fall back to [color].
     */
    fun apply(
        src: Bitmap,
        mode: OutlineMode,
        widthPx: Float,
        color: Int,
        style: ColorizerStyle? = null
    ): Bitmap {
        val gradient = style?.takeIf { it.mode == ColorizerMode.GRADIENT }
        return when (mode) {
            OutlineMode.NONE -> src
            OutlineMode.ADD -> addOutline(src, widthPx, color, gradient)
            OutlineMode.RECOLOR -> recolorOutline(src, widthPx, color, gradient)
        }
    }

    /** The gradient rasterised once, so per-pixel work can look up its colour by position. */
    private fun gradientPixels(style: ColorizerStyle, width: Int, height: Int): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = buildColorizerShader(
                    style.allGradientColors,
                    style.gradientType,
                    style.gradientAngle,
                    width,
                    height
                )
            }
        )
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels
    }

    /**
     * Draws a contour of [color] hugging the icon's silhouette: the icon's alpha mask is
     * stamped in a ring of offsets around every direction (a cheap dilation), then the icon
     * itself is drawn back on top — the colour stays visible only in the [widthPx] band
     * outside the original edges.
     */
    private fun addOutline(
        src: Bitmap,
        widthPx: Float,
        color: Int,
        gradient: ColorizerStyle?
    ): Bitmap {
        val width = max(1f, widthPx)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val mask = src.extractAlpha()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (gradient == null) {
            paint.color = color
        } else {
            // The shader spans the whole icon, so every stamped mask samples the same gradient
            // and the ring reads as one continuous sweep rather than 32 separate fills.
            paint.shader = buildColorizerShader(
                gradient.allGradientColors,
                gradient.gradientType,
                gradient.gradientAngle,
                src.width,
                src.height
            )
        }

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

    // How far (CIE76 ΔE in Lab) a pixel may sit from the outline's boundary palette and still
    // count as outline. Generous enough for shading within the outline colour, small enough
    // that the halfway blend of an antialiased outline/fill edge already falls outside.
    private const val DELTA_E_TOLERANCE = 30f

    // Boundary pixels below this alpha are too noisy to describe the outline's colour
    // (unpremultiplied low-alpha RGB is garbage) — they get selected, but never vote
    // for the reference palette.
    private const val PALETTE_MIN_ALPHA = 200

    /**
     * Repaints the icon's EXISTING outline instead of adding one. The outline is found by an
     * edge-stopping flood: a colour palette is sampled where the icon meets transparency (the
     * outline's own colours, by construction), then the flood grows inward from that boundary
     * as long as pixels stay within [DELTA_E_TOLERANCE] of the palette in CIELAB — it stops at
     * the perceptual colour jump where the outline meets the icon's fill, whatever the
     * outline's thickness ([widthPx] is deliberately ignored; a depth cap used to cut thick
     * outlines in half). The repaint transfers [color]'s hue and saturation but scales each
     * pixel's own brightness relative to the outline's core, so soft edges and gradients keep
     * their shading in the new colour instead of flattening.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun recolorOutline(
        src: Bitmap,
        widthPx: Float,
        color: Int,
        gradient: ColorizerStyle?
    ): Bitmap {
        val w = src.width
        val h = src.height
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

        val palette = boundaryPalette(pixels, depth)
        val labScratch = FloatArray(3)

        // Edge-stopping flood: grow to visible 4-neighbours that are still perceptually close
        // to the boundary palette. No depth cap — the colour jump at the fill is the stop.
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
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
                if (!nearPalette(pixels[n], palette, labScratch)) continue
                depth[n] = depth[i] + 1
                queue.add(n)
            }
        }

        // Brightness reference: the brightest value among the outline's CORE pixels (past the
        // antialiased first layers), so the fringe scales below it instead of clipping. A
        // selection too thin to have a core falls back to every selected pixel.
        val hsv = FloatArray(3)
        var refValue = 0f
        for (coreOnly in booleanArrayOf(true, false)) {
            for (i in pixels.indices) {
                if (if (coreOnly) depth[i] >= 2 else depth[i] >= 0) {
                    Color.colorToHSV(pixels[i], hsv)
                    if (hsv[2] > refValue) refValue = hsv[2]
                }
            }
            if (refValue > 0f) break
        }
        if (refValue <= 0f) refValue = 1f

        val target = FloatArray(3)
        Color.colorToHSV(color, target)
        // A gradient gives every repainted pixel its own hue/saturation from the same sweep.
        val gradientAt = gradient?.let { gradientPixels(it, w, h) }
        for (i in pixels.indices) {
            if (depth[i] < 0) continue
            gradientAt?.let { Color.colorToHSV(it[i], target) }
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

    /**
     * The outline's own colours, sampled where the icon meets transparency (depth-0 seeds).
     * Seeds are bucketed at 4 bits per channel and each bucket that holds a meaningful share
     * of the boundary contributes its average colour — so a gradient outline yields several
     * palette entries while lone noise pixels yield none. Only solid seeds vote: low-alpha
     * unpremultiplied RGB is noise. Returns packed L,a,b triples.
     */
    private fun boundaryPalette(pixels: IntArray, depth: IntArray): FloatArray {
        // bucket key -> [count, sumR, sumG, sumB]
        val buckets = HashMap<Int, LongArray>()
        var votes = 0
        for (minAlpha in intArrayOf(PALETTE_MIN_ALPHA, 40, 1)) {
            for (i in pixels.indices) {
                if (depth[i] != 0 || (pixels[i] ushr 24) < minAlpha) continue
                val r = pixels[i] shr 16 and 0xFF
                val g = pixels[i] shr 8 and 0xFF
                val b = pixels[i] and 0xFF
                val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
                val acc = buckets.getOrPut(key) { LongArray(4) }
                acc[0]++
                acc[1] += r
                acc[2] += g
                acc[3] += b
                votes++
            }
            if (votes > 0) break // only fall back to fringier seeds when no solid ones exist
        }
        val minCount = max(1, votes / 100)
        val scratch = FloatArray(3)
        val lab = ArrayList<Float>(buckets.size * 3)
        for (acc in buckets.values) {
            if (acc[0] < minCount) continue
            rgbToLab(
                (acc[1] / acc[0]).toInt(),
                (acc[2] / acc[0]).toInt(),
                (acc[3] / acc[0]).toInt(),
                scratch
            )
            lab.add(scratch[0]); lab.add(scratch[1]); lab.add(scratch[2])
        }
        return lab.toFloatArray()
    }

    private fun nearPalette(pixel: Int, palette: FloatArray, scratch: FloatArray): Boolean {
        if (palette.isEmpty()) return false
        rgbToLab(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF, scratch)
        var p = 0
        while (p < palette.size) {
            val dl = scratch[0] - palette[p]
            val da = scratch[1] - palette[p + 1]
            val db = scratch[2] - palette[p + 2]
            if (dl * dl + da * da + db * db <= DELTA_E_TOLERANCE * DELTA_E_TOLERANCE) return true
            p += 3
        }
        return false
    }

    // sRGB -> linear lookup, built once; the flood converts every visited pixel.
    private val srgbToLinear = DoubleArray(256) { i ->
        val c = i / 255.0
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** sRGB (D65) to CIELAB, for perceptual colour distances. */
    private fun rgbToLab(r: Int, g: Int, b: Int, out: FloatArray) {
        val rl = srgbToLinear[r]
        val gl = srgbToLinear[g]
        val bl = srgbToLinear[b]
        // XYZ, normalized to the D65 white point.
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / 0.95047
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / 1.08883
        fun f(t: Double): Double = if (t > 0.008856) Math.cbrt(t) else (7.787 * t + 16.0 / 116.0)
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        out[0] = (116.0 * fy - 16.0).toFloat()
        out[1] = (500.0 * (fx - fy)).toFloat()
        out[2] = (200.0 * (fy - fz)).toFloat()
    }
}
