package dev.renkinProject.renkin.extension

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

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
 * Erases the background around an icon, fully on-device. Floods inward from the edges: it crosses
 * any transparent padding for free, reads the background colour from the first opaque "shoreline" it
 * reaches (the most common such colour), then keeps growing into opaque pixels — but only while each
 * step stays within [tolerance] of the colour it came from. Comparing against the *neighbour* (not a
 * single fixed colour) lets a gradient background be followed, while a hard glyph edge (a large
 * colour jump) stops the fill, so the inner artwork survives. [tolerance] is 0..1, a fraction of the
 * max RGB distance. Returns the original when there's no opaque content reachable from the border.
 */
fun Bitmap.removeBackground(tolerance: Float): Bitmap {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return this

    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    val thresholdSq = (tolerance.coerceIn(0f, 1f) * 441.673f).let { it * it } // 441.673 = sqrt(3*255^2)

    fun isTransparent(c: Int) = (c ushr 24) < 16
    fun distSq(c: Int, rr: Int, rg: Int, rb: Int): Float {
        val dr = ((c shr 16) and 0xFF) - rr
        val dg = ((c shr 8) and 0xFF) - rg
        val db = (c and 0xFF) - rb
        return (dr * dr + dg * dg + db * db).toFloat()
    }

    // Pass A — flood the border-connected transparent region and tally the opaque "shoreline" it
    // touches (and any opaque border pixels). The most common shoreline colour is the background.
    val seenA = BooleanArray(w * h)
    val stackA = ArrayDeque<Int>()
    val shoreline = HashMap<Int, Int>()
    fun visitA(i: Int) {
        if (seenA[i]) return
        seenA[i] = true
        val c = pixels[i]
        if (isTransparent(c)) stackA.addLast(i)
        else { val rgb = c and 0x00FFFFFF; shoreline[rgb] = (shoreline[rgb] ?: 0) + 1 }
    }
    for (x in 0 until w) { visitA(x); visitA((h - 1) * w + x) }
    for (y in 0 until h) { visitA(y * w); visitA(y * w + w - 1) }
    while (stackA.isNotEmpty()) {
        val i = stackA.removeLast()
        val x = i % w
        if (x > 0) visitA(i - 1)
        if (x < w - 1) visitA(i + 1)
        if (i >= w) visitA(i - w)
        if (i < (h - 1) * w) visitA(i + w)
    }
    if (shoreline.isEmpty()) return this

    val ref = shoreline.maxByOrNull { it.value }!!.key
    val refR = (ref shr 16) and 0xFF
    val refG = (ref shr 8) and 0xFF
    val refB = ref and 0xFF

    // Pass B — clear the background. Seed from the border (transparent, or opaque matching the
    // reference), then grow: transparent neighbours are always cleared; an opaque neighbour is
    // cleared only if it's within tolerance of the colour it was reached from (the reference when
    // crossing from transparent, otherwise the current pixel's own colour — following a gradient).
    val out = pixels.copyOf()
    val seenB = BooleanArray(w * h)
    val stackB = ArrayDeque<Int>()
    fun clearBg(i: Int) {
        if (seenB[i]) return
        seenB[i] = true
        out[i] = 0
        stackB.addLast(i)
    }
    fun consider(j: Int, rr: Int, rg: Int, rb: Int) {
        if (seenB[j]) return
        val cj = pixels[j]
        if (isTransparent(cj) || distSq(cj, rr, rg, rb) <= thresholdSq) clearBg(j)
    }
    for (x in 0 until w) { consider(x, refR, refG, refB); consider((h - 1) * w + x, refR, refG, refB) }
    for (y in 0 until h) { consider(y * w, refR, refG, refB); consider(y * w + w - 1, refR, refG, refB) }
    while (stackB.isNotEmpty()) {
        val i = stackB.removeLast()
        val ci = pixels[i]
        val rr: Int; val rg: Int; val rb: Int
        if (isTransparent(ci)) { rr = refR; rg = refG; rb = refB }
        else { rr = (ci shr 16) and 0xFF; rg = (ci shr 8) and 0xFF; rb = ci and 0xFF }
        val x = i % w
        if (x > 0) consider(i - 1, rr, rg, rb)
        if (x < w - 1) consider(i + 1, rr, rg, rb)
        if (i >= w) consider(i - w, rr, rg, rb)
        if (i < (h - 1) * w) consider(i + w, rr, rg, rb)
    }

    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/**
 * Bounding box of the visible content (right/bottom exclusive), or null when fully transparent.
 * Noise-tolerant: a row/column only counts as content when it holds more than ~1.5% opaque pixels,
 * so a stray speck (an antialias fringe, background-removal leftovers) doesn't inflate the box to
 * pixels the eye can't see.
 */
fun Bitmap.contentBounds(): Rect? {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)

    val colCounts = IntArray(w)
    val rowCounts = IntArray(h)
    for (y in 0 until h) for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) >= 16) { colCounts[x]++; rowCounts[y]++ }
    }
    // ~1.5% of the line, but never less than one pixel (tiny bitmaps keep exact behaviour).
    val colFloor = h / 64 + 1
    val rowFloor = w / 64 + 1
    val left = colCounts.indexOfFirst { it >= colFloor }
    val top = rowCounts.indexOfFirst { it >= rowFloor }
    if (left < 0 || top < 0) return null
    val right = colCounts.indexOfLast { it >= colFloor }
    val bottom = rowCounts.indexOfLast { it >= rowFloor }
    return Rect(left, top, right + 1, bottom + 1)
}

/** Shifts the whole image by ([dx], [dy]) whole pixels; content pushed off the edge is dropped. */
fun Bitmap.translated(dx: Float, dy: Float): Bitmap {
    val idx = dx.roundToInt()
    val idy = dy.roundToInt()
    if (idx == 0 && idy == 0) return this
    val w = width
    val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val sx = x - idx
        val sy = y - idy
        if (sx in 0 until w && sy in 0 until h) out[y * w + x] = px[sy * w + sx]
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

private val standardBase64Pattern = Regex("[A-Za-z0-9+/]*={0,2}")

fun bitmapFromBase64(base64: String, base64Flag: Int = Base64.NO_WRAP): Bitmap {
    if (base64Flag == Base64.NO_WRAP) {
        require(base64.length % 4 == 0 && standardBase64Pattern.matches(base64)) {
            "Stored bitmap data is not valid Base64"
        }
    }
    val bytes = Base64.decode(base64, base64Flag)
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
        "Stored bitmap data could not be decoded"
    }
}
