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