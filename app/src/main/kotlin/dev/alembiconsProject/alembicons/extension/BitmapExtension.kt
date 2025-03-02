package dev.alembiconsProject.alembicons.extension

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

fun Bitmap.changeBackgroundColor(color: Int): Bitmap {
    val newBitmap = this.clone()
    val canvas = Canvas(newBitmap)
    canvas.drawColor(color)
    canvas.drawBitmap(this, 0F, 0F, null)
    recycle()
    return newBitmap
}

fun Bitmap.clone(): Bitmap {
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

fun bitmapFromBase64(base64: String, base64Flag: Int = Base64.NO_WRAP): Bitmap {
    val bytes = Base64.decode(base64, base64Flag)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}