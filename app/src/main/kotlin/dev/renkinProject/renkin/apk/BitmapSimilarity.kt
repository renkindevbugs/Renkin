package dev.renkinProject.renkin.apk

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Downscaled per-channel comparison with a small tolerance — the generation pipeline
 * may rescale/re-encode, so exact equality would reject untouched icons too.
 */
internal fun Bitmap.looksAlike(other: Bitmap): Boolean = averageDifference(other) < 8

/**
 * Average per-channel difference at [size]×[size]. Ranks near-identical images, such as calendar
 * days that differ only in their digits, where [looksAlike] accepts all of them.
 */
internal fun Bitmap.averageDifference(other: Bitmap, size: Int = 32): Long {
    val sa = Bitmap.createScaledBitmap(this, size, size, true)
    val sb = Bitmap.createScaledBitmap(other, size, size, true)
    var diff = 0L
    for (y in 0 until size) {
        for (x in 0 until size) {
            val pa = sa.getPixel(x, y)
            val pb = sb.getPixel(x, y)
            diff += abs(Color.alpha(pa) - Color.alpha(pb)) +
                abs(Color.red(pa) - Color.red(pb)) +
                abs(Color.green(pa) - Color.green(pb)) +
                abs(Color.blue(pa) - Color.blue(pb))
        }
    }
    return diff / (size * size * 4)
}
