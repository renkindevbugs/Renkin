package dev.alembiconsProject.alembicons.drawable

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.Px
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toBitmapOrNull
import dev.alembiconsProject.alembicons.packages.PackageVersion

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

fun Drawable.hasValidDimensions(): Boolean {
    return intrinsicWidth > 0 && intrinsicHeight > 0
}

fun Drawable.toIconPackDrawable(): IconPackDrawable? {
    if (this.isAdaptiveIconDrawable()) {
        return (this as AdaptiveIconDrawable).toIconPackDrawable()
    }

    return when (this) {
        is IconPackDrawable -> this
        is BitmapDrawable -> BitmapIconDrawable(this)
        is InsetDrawable -> this.toIconPackDrawable()
        is ColorDrawable -> BitmapDrawable(null, this.toBitmap(108, 108)).toIconPackDrawable()
        else -> null
    }
}

fun AdaptiveIconDrawable.toIconPackDrawable(monochrome: Boolean = false): IconPackDrawable? {
    if (monochrome && this.haveMonochrome()) {
        return this.monochrome!!.toIconPackDrawable()
    }

    return this.foreground.toIconPackDrawable()
}

fun InsetDrawable.toIconPackDrawable(): IconPackDrawable?  {
    if (this.drawable == null) return null

    return this.drawable!!.toIconPackDrawable()
}

@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun AdaptiveIconDrawable.haveMonochrome(): Boolean {
    if (PackageVersion.is33OrMore()) {
        return this.monochrome != null
    }

    return false
}

@RequiresApi(Build.VERSION_CODES.O)
fun newAdaptiveIconDrawable(foreground: Drawable, background: Drawable, monochrome: Drawable?): AdaptiveIconDrawable {
    if (PackageVersion.is33OrMore()) {
        return AdaptiveIconDrawable(background, foreground, monochrome)
    }

    return AdaptiveIconDrawable(background, foreground)
}

fun InsetDrawable.getInsetValues(dimensions: Rect, fractions: RectF) {
    val bounds = Rect(this.bounds)

    val rect1 = Rect(0, 0, 100, 100)
    this.bounds = rect1
    this.getPadding(rect1)

    val rect2 = Rect(0, 0, 200, 200)
    this.bounds = rect2
    this.getPadding(rect2)

    val rect3 = Rect(0, 0, intrinsicWidth, intrinsicHeight)
    this.bounds = rect3
    this.getPadding(rect3)

    this.bounds = bounds

    dimensions.left = if (rect1.left == rect2.left) rect1.left else rect3.left
    dimensions.right = if (rect1.right == rect2.right) rect1.right else rect3.right
    dimensions.top = if (rect1.top == rect2.top) rect1.top else rect3.top
    dimensions.bottom = if (rect1.bottom == rect2.bottom) rect1.bottom else rect3.bottom

    fractions.left = if (rect1.left != rect2.left) rect1.left / 100f else -1f
    fractions.right = if (rect1.right != rect2.right) rect1.right / 100f else -1f
    fractions.top = if (rect1.top != rect2.top) rect1.top / 100f else -1f
    fractions.bottom = if (rect1.bottom != rect2.bottom) rect1.bottom / 100f else -1f
}