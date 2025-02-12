package com.kaanelloed.iconeration.drawable

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.Px
import androidx.core.graphics.drawable.toBitmapOrNull
import com.kaanelloed.iconeration.packages.PackageVersion

class DrawableExtension {
    companion object {
        fun Drawable.shrinkIfBiggerThan(size: Int): Bitmap? {
            val maxWidthOrHeight = kotlin.math.max(this.intrinsicWidth, this.intrinsicHeight)
            if (maxWidthOrHeight > size) {
                val multi = size / maxWidthOrHeight.toFloat()
                val newWidth = (this.intrinsicWidth * multi).toInt()
                val newHeight = (this.intrinsicHeight * multi).toInt()

                return this.toSafeBitmapOrNull(newWidth, newHeight)
            }

            return this.toSafeBitmapOrNull()
        }

        @ChecksSdkIntAtLeast(Build.VERSION_CODES.O)
        fun Drawable.isAdaptiveIconDrawable(): Boolean {
            if (PackageVersion.is26OrMore()) {
                return this is AdaptiveIconDrawable
            }

            return false
        }

        fun Drawable.toSafeBitmapOrNull(
            @Px width: Int = intrinsicWidth
            , @Px height: Int = intrinsicHeight
            , config: Config? = null
        ): Bitmap? {
            if (width <= 0 || height <= 0)
                return null

            return toBitmapOrNull(width, height, config)
        }

        fun Drawable.sizeIsGreaterThanZero(): Boolean {
            return intrinsicWidth > 0 && intrinsicHeight > 0
        }
    }
}