package com.kaanelloed.iconeration.extension

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

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