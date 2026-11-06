package dev.renkinProject.renkin.apk

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Downscaled per-channel comparison with a small tolerance — the generation pipeline
 * may rescale/re-encode, so exact equality would reject untouched icons too.
 */
internal fun Bitmap.looksAlike(other: Bitmap): Boolean {
    val n = 32
    val sa = Bitmap.createScaledBitmap(this, n, n, true)
    val sb = Bitmap.createScaledBitmap(other, n, n, true)
    var diff = 0L
    for (y in 0 until n) {
        for (x in 0 until n) {
            val pa = sa.getPixel(x, y)
            val pb = sb.getPixel(x, y)
            diff += abs(Color.alpha(pa) - Color.alpha(pb)) +
                abs(Color.red(pa) - Color.red(pb)) +
                abs(Color.green(pa) - Color.green(pb)) +
                abs(Color.blue(pa) - Color.blue(pb))
        }
    }
    // Average difference per pixel across the four channels.
    return diff / (n * n * 4) < 8
}
