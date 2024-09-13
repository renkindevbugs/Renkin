package com.kaanelloed.iconeration.drawable

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.packages.PackageVersion

class DrawableExtension {
    companion object {
        fun Drawable.shrinkIfBiggerThan(size: Int): Bitmap {
            val maxWidthOrHeight = kotlin.math.max(this.intrinsicWidth, this.intrinsicHeight)
            if (maxWidthOrHeight > size) {
                val multi = size / maxWidthOrHeight.toFloat()
                val newWidth = (this.intrinsicWidth * multi).toInt()
                val newHeight = (this.intrinsicHeight * multi).toInt()

                return this.toBitmap(newWidth, newHeight)
            }

            return this.toBitmap()
        }

        @Suppress("DEPRECATION")
        fun Bitmap.toDrawable(): Drawable {
            return BitmapDrawable(this)
        }

        @ChecksSdkIntAtLeast(Build.VERSION_CODES.O)
        fun Drawable.isAdaptiveIconDrawable(): Boolean {
            if (PackageVersion.is26OrMore()) {
                return this is AdaptiveIconDrawable
            }

            return false
        }
    }
}