package dev.alembiconsProject.alembicons.extension

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Creates a blank ARGB_8888 bitmap of [width]×[height], runs [draw] on a Canvas backed by it and
 * returns the bitmap — wrapping the repeated `createBitmap(...) + Canvas(it)` compositing boilerplate.
 */
inline fun newArgbBitmap(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap
}

/**
 * Erases the flat background around an icon, fully on-device. Reads the background colour from the
 * opaque border pixels, then flood-fills inward from every edge, clearing any connected pixel whose
 * colour is within [tolerance] (0..1, a fraction of the max RGB distance) of that background — so a
 * solid (or slightly graded) backdrop is removed while the centred glyph, not connected to the
 * border in that colour, is kept. Returns the original if the border is already transparent.
 */
fun Bitmap.removeBackground(tolerance: Float): Bitmap {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return this

    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    // Reference background colour = the most common opaque border colour. The mode (not the
    // average) so a glyph that reaches the edge can't pull the reference off the real background.
    val borderCounts = HashMap<Int, Int>()
    fun sample(i: Int) {
        val c = pixels[i]
        if ((c ushr 24) < 16) return
        val rgb = c and 0x00FFFFFF
        borderCounts[rgb] = (borderCounts[rgb] ?: 0) + 1
    }
    for (x in 0 until w) { sample(x); sample((h - 1) * w + x) }
    for (y in 1 until h - 1) { sample(y * w); sample(y * w + w - 1) }
    if (borderCounts.isEmpty()) return this

    val reference = borderCounts.maxByOrNull { it.value }!!.key
    val refR = (reference shr 16) and 0xFF
    val refG = (reference shr 8) and 0xFF
    val refB = reference and 0xFF
    val maxDistance = 441.673f // sqrt(3 * 255^2)
    val thresholdSq = (tolerance.coerceIn(0f, 1f) * maxDistance).let { it * it }

    fun isBackground(c: Int): Boolean {
        if ((c ushr 24) < 16) return true
        val dr = ((c shr 16) and 0xFF) - refR
        val dg = ((c shr 8) and 0xFF) - refG
        val db = (c and 0xFF) - refB
        return (dr * dr + dg * dg + db * db).toFloat() <= thresholdSq
    }

    val out = pixels.copyOf()
    val visited = BooleanArray(w * h)
    val stack = ArrayDeque<Int>()
    fun push(i: Int) {
        if (!visited[i] && isBackground(pixels[i])) {
            visited[i] = true
            stack.addLast(i)
        }
    }
    for (x in 0 until w) { push(x); push((h - 1) * w + x) }
    for (y in 0 until h) { push(y * w); push(y * w + w - 1) }

    while (stack.isNotEmpty()) {
        val i = stack.removeLast()
        out[i] = 0
        val x = i % w
        if (x > 0) push(i - 1)
        if (x < w - 1) push(i + 1)
        if (i >= w) push(i - w)
        if (i < (h - 1) * w) push(i + w)
    }

    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

fun Bitmap.changeBackgroundColor(color: Int): Bitmap {
    val newBitmap = this.emptyLike()
    val canvas = Canvas(newBitmap)
    canvas.drawColor(color)
    canvas.drawBitmap(this, 0F, 0F, null)
    recycle()
    return newBitmap
}

/**
 * Scales the content up around the centre, keeping the same dimensions (the
 * overflow is cropped). Used to fill the frame with an adaptive icon's
 * foreground, whose artwork only occupies the inner 72/108 safe zone (#80).
 */
fun Bitmap.scaleFromCenter(scale: Float): Bitmap {
    if (scale == 1f) return this

    val result = Bitmap.createBitmap(width, height, config ?: Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val matrix = Matrix().apply { postScale(scale, scale, width / 2f, height / 2f) }
    canvas.drawBitmap(this, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

    return result
}

/**
 * A new **blank** bitmap matching this one's size, config and density — NOT a pixel copy.
 * Meant as a fresh canvas to composite onto (a background, a colour-filtered redraw, …).
 */
fun Bitmap.emptyLike(): Bitmap {
    val newBitmap = Bitmap.createBitmap(width, height, config!!)
    newBitmap.density = density
    return newBitmap
}

fun Bitmap.toDrawable(res: Resources): Drawable {
    return BitmapDrawable(res, this)
}

fun Bitmap.getBytes(format: Bitmap.CompressFormat, quality: Int): ByteArray {
    val outStream = ByteArrayOutputStream()
    this.compress(format, quality, outStream)
    val bytes = outStream.toByteArray()
    outStream.close()

    return bytes
}

fun Bitmap.toBase64(format: Bitmap.CompressFormat, quality: Int, base64Flag: Int = Base64.NO_WRAP): String {
    val bytes = this.getBytes(format, quality)
    return Base64.encodeToString(bytes, base64Flag)
}

/**
 * A content fingerprint of the pixels, used to tell whether an icon pack changed an
 * app's icon (#icon-watch). PNG encoding is deterministic for identical pixels.
 */
fun Bitmap.contentHash(): String {
    val bytes = getBytes(Bitmap.CompressFormat.PNG, 100)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun bitmapFromBase64(base64: String, base64Flag: Int = Base64.NO_WRAP): Bitmap {
    val bytes = Base64.decode(base64, base64Flag)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}