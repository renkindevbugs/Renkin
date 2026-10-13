package dev.renkinProject.renkin.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.extension.bitmapFromBase64
import dev.renkinProject.renkin.extension.scaleFromCenter
import dev.renkinProject.renkin.extension.toBase64

internal const val ADAPTIVE_ICON_SCALE = 1.5f // 108dp / 72dp

/**
 * Live artwork keeps its original bitmap. Stored PNG artwork keeps validated encoded pixels:
 * preview requests decode to their target size, while export/edit requests receive full-size
 * detached bitmaps. Neither previews nor full-size reads mutate the stored source.
 * [previewScale] affects in-app previews only; browser previews and export retain raw geometry.
 */
class BitmapIconDrawable private constructor(
    @Volatile private var resident: BitmapDrawable?,
    private val stored: StoredBitmapPixels?,
    private val exportAsAdaptiveIcon: Boolean,
    val previewScale: Float,
    private val browserPreviewBitmap: Bitmap?
) : IconPackDrawable() {
    constructor(
        drawable: BitmapDrawable,
        exportAsAdaptiveIcon: Boolean = false,
        previewScale: Float = 1f,
        browserPreviewBitmap: Bitmap? = null
    ) : this(drawable, null, exportAsAdaptiveIcon, previewScale, browserPreviewBitmap)

    constructor(
        bitmap: Bitmap,
        exportAsAdaptiveIcon: Boolean = false,
        previewScale: Float = 1f,
        browserPreviewBitmap: Bitmap? = null
    ) : this(BitmapDrawable(null, bitmap), exportAsAdaptiveIcon, previewScale, browserPreviewBitmap)

    constructor(
        resources: Resources,
        bitmap: Bitmap,
        exportAsAdaptiveIcon: Boolean = false,
        previewScale: Float = 1f,
        browserPreviewBitmap: Bitmap? = null
    ) : this(BitmapDrawable(resources, bitmap), exportAsAdaptiveIcon, previewScale, browserPreviewBitmap)

    private val contentBounds = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Explicit mutable Drawable access preserves the old API, but opts this instance into
    // resident pixels. Ordinary stored preview/export paths never need that mutable wrapper.
    val drawable: BitmapDrawable
        get() = resident ?: synchronized(this) {
            resident ?: BitmapDrawable(null, requireNotNull(stored).decode()).apply {
                bounds = contentBounds
                alpha = paint.alpha
                colorFilter = paint.colorFilter
            }.also { resident = it }
        }

    override fun draw(canvas: Canvas) {
        resident?.let { it.draw(canvas); return }
        val size = maxOf(contentBounds.width(), contentBounds.height())
        if (size <= 0) return
        val bitmap = if (previewScale == 1f) {
            IconPreviewCache.getOrRender(IconPreviewCacheKey(this, size)) ?: return
        } else requireNotNull(stored).preview(size)
        // A hardware Canvas may retain this bitmap in its display list after draw() returns.
        canvas.drawBitmap(bitmap, null, contentBounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        resident?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        resident?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("drawable.opacity"))
    override fun getOpacity(): Int = resident?.opacity ?: if (paint.alpha < 255 || requireNotNull(stored).hasAlpha) {
        PixelFormat.TRANSLUCENT
    } else PixelFormat.OPAQUE

    override fun getIntrinsicWidth(): Int = resident?.intrinsicWidth ?: requireNotNull(stored).intrinsicWidth
    override fun getIntrinsicHeight(): Int = resident?.intrinsicHeight ?: requireNotNull(stored).intrinsicHeight

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        contentBounds.set(left, top, right, bottom)
        resident?.setBounds(left, top, right, bottom)
    }

    override fun setBounds(bounds: Rect) = setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)

    @Composable
    override fun getPainter(): Painter {
        if (resident == null) {
            val pixels = requireNotNull(stored)
            val target = maxOf(pixels.width, pixels.height).coerceAtMost(500)
            val scale = target.toFloat() / maxOf(pixels.width, pixels.height)
            return rememberPreviewPainter(target, Size(pixels.width * scale, pixels.height * scale))
        }
        val previewBitmap = inAppPreviewBitmap()
        val bitmap = if (previewScale != 1f) {
            remember(previewBitmap, previewScale) { previewBitmap.scaleFromCenter(previewScale) }
        } else previewBitmap
        return BitmapPainter(bitmap.asImageBitmap())
    }

    internal fun inAppPreviewBitmap(): Bitmap = toBitmap()

    override fun previewBitmap(): Bitmap {
        resident?.bitmap?.let { return if (previewScale == 1f) it else it.scaleFromCenter(previewScale) }
        val pixels = requireNotNull(stored)
        return previewBitmap(maxOf(pixels.width, pixels.height))
    }

    override fun previewBitmap(sizePx: Int): Bitmap {
        if (resident != null) return super.previewBitmap(sizePx)
        val bitmap = requireNotNull(stored).preview(sizePx)
        if (previewScale == 1f) return bitmap
        return try { bitmap.scaleFromCenter(previewScale) } finally { bitmap.recycle() }
    }

    override fun toBitmap(): Bitmap = resident?.bitmap ?: requireNotNull(stored).decode()

    override fun toBrowserPreviewBitmap(): Bitmap = browserPreviewBitmap ?: toBitmap()

    override fun toBrowserPreviewBitmap(sizePx: Int): Bitmap = if (resident == null) {
        requireNotNull(stored).preview(sizePx)
    } else super.toBrowserPreviewBitmap(sizePx)

    override fun toDbString(): String = resident?.bitmap?.toBase64(Bitmap.CompressFormat.PNG, 100)
        ?: requireNotNull(stored).encoded

    override fun isAdaptiveIcon(): Boolean = exportAsAdaptiveIcon

    internal fun detachedStoredCopy(): BitmapIconDrawable? = stored?.takeIf { resident == null }?.let {
        BitmapIconDrawable(null, it, exportAsAdaptiveIcon, previewScale, null)
    }

    companion object {
        internal fun fromStored(payload: String, adaptive: Boolean): BitmapIconDrawable {
            val bitmap = bitmapFromBase64(payload)
            val scale = if (adaptive) ADAPTIVE_ICON_SCALE else 1f
            if (!payload.startsWith("iVBORw0KGgo")) return BitmapIconDrawable(bitmap, adaptive, scale)
            return try {
                BitmapIconDrawable(null, StoredBitmapPixels(payload, bitmap), adaptive, scale, null)
            } finally {
                bitmap.recycle() // Validation pixels have no owner outside this factory.
            }
        }
    }
}
