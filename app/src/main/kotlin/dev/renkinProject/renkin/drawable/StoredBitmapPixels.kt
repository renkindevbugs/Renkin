package dev.renkinProject.renkin.drawable

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64

/** Immutable stored pixels shared by independent Drawable wrappers, never by mutable bitmaps. */
internal class StoredBitmapPixels(val encoded: String, validated: Bitmap) {
    val width = validated.width
    val height = validated.height
    private val density = validated.density
    val hasAlpha = validated.hasAlpha()
    val intrinsicWidth: Int
    val intrinsicHeight: Int

    init {
        val drawable = BitmapDrawable(null, validated)
        intrinsicWidth = drawable.intrinsicWidth
        intrinsicHeight = drawable.intrinsicHeight
    }

    fun decode(): Bitmap = decode(1)

    fun preview(sizePx: Int): Bitmap {
        require(sizePx > 0)
        val extent = maxOf(width, height)
        var sample = 1
        while (extent / (sample * 2) >= sizePx) sample *= 2
        val bitmap = decode(sample)
        val scale = minOf(1f, sizePx.toFloat() / extent)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        if (bitmap.width == targetWidth && bitmap.height == targetHeight) return bitmap
        return try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } finally {
            bitmap.recycle() // Only this private intermediate was decoded; the result is distinct.
        }
    }

    private fun decode(sample: Int): Bitmap {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) {
            "Stored bitmap pixels could not be decoded"
        }.apply { density = this@StoredBitmapPixels.density }
    }
}
