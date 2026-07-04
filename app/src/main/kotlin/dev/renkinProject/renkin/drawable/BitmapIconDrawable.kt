package dev.renkinProject.renkin.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.extension.scaleFromCenter
import dev.renkinProject.renkin.extension.toBase64

/**
 * [previewScale] zooms the icon only in the in-app preview ([getPainter]), not in the exported
 * bitmap ([toBitmap]). Adaptive foregrounds (e.g. the monochrome variant) keep their artwork in
 * the inner safe zone, which the launcher zooms into — the flat preview doesn't, so it would look
 * too small; this scales the preview to match without affecting what the launcher receives.
 */
class BitmapIconDrawable(
    val drawable: BitmapDrawable,
    private val exportAsAdaptiveIcon: Boolean = false,
    val previewScale: Float = 1f
) :
    IconPackDrawable() {
    constructor(bitmap: Bitmap, exportAsAdaptiveIcon: Boolean = false, previewScale: Float = 1f) : this(
        BitmapDrawable(
            null,
            bitmap
        ), exportAsAdaptiveIcon, previewScale
    )

    constructor(resources: Resources, bitmap: Bitmap, exportAsAdaptiveIcon: Boolean = false, previewScale: Float = 1f) : this(
        BitmapDrawable(
            resources,
            bitmap
        ), exportAsAdaptiveIcon, previewScale
    )

    override fun draw(canvas: Canvas) {
        drawable.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        drawable.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawable.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("drawable.opacity"))
    override fun getOpacity(): Int {
        return drawable.opacity
    }

    override fun getIntrinsicWidth(): Int {
        return drawable.intrinsicWidth
    }

    override fun getIntrinsicHeight(): Int {
        return drawable.intrinsicHeight
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        drawable.setBounds(left, top, right, bottom)
    }

    override fun setBounds(bounds: Rect) {
        drawable.bounds = bounds
    }

    @Composable
    override fun getPainter(): Painter {
        val bitmap = if (previewScale != 1f) {
            remember(drawable.bitmap, previewScale) { drawable.bitmap.scaleFromCenter(previewScale) }
        } else {
            drawable.bitmap
        }
        return BitmapPainter(bitmap.asImageBitmap())
    }

    override fun toBitmap(): Bitmap {
        return drawable.bitmap
    }

    override fun toDbString(): String {
        return drawable.bitmap.toBase64(Bitmap.CompressFormat.PNG, 100)
    }

    override fun isAdaptiveIcon(): Boolean {
        return exportAsAdaptiveIcon
    }
}