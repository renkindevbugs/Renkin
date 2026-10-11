package dev.renkinProject.renkin.drawable

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.parser.AdaptiveIconPayload

data class MaterialYouPackEditState(
    val selectedScheme: Int,
    val customForeground: ColorizerStyle,
    val customBackground: ColorizerStyle,
    val strokeScale: Float
) {
    companion object {
        val DEFAULT_FOREGROUND = ColorizerStyle(firstColor = android.graphics.Color.WHITE)
        val DEFAULT_BACKGROUND = ColorizerStyle(firstColor = android.graphics.Color.BLACK)
    }
}

class AdaptiveIconPackDrawable internal constructor(
    internal val foregroundPng: ByteArray,
    internal val backgroundPng: ByteArray,
    internal val monochromePng: ByteArray? = null,
    val materialYouEditState: MaterialYouPackEditState? = null,
    internal val originalForegroundPng: ByteArray? = null,
    internal val originalBackgroundPng: ByteArray? = null
) : IconPackDrawable() {
    private constructor(
        foreground: Bitmap,
        background: Bitmap,
        monochrome: Bitmap? = null,
        materialYouEditState: MaterialYouPackEditState? = null,
        originalForegroundPng: ByteArray? = null,
        originalBackgroundPng: ByteArray? = null
    ) : this(
        encodeLayer(foreground),
        encodeLayer(background),
        monochrome?.let(::encodeLayer),
        materialYouEditState,
        originalForegroundPng,
        originalBackgroundPng
    )

    // A pack can contain thousands of icons; retain compressed layers, not three large pixel buffers.
    val foreground: Bitmap get() = decodeLayer(foregroundPng)
    val background: Bitmap get() = decodeLayer(backgroundPng)
    val monochrome: Bitmap? get() = monochromePng?.let { decodeLayer(it) }
    val hasMonochrome: Boolean get() = monochromePng != null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun isAdaptiveIcon(): Boolean = true
    override fun toBitmap(): Bitmap = render(PREVIEW_SIZE)
    // Flattening modifiers produce a legacy bitmap shown without viewport compensation, so they
    // start from the compensated preview artwork; otherwise the icon shrinks by 72/108 once enabled.
    // The launcher mask is reapplied at frame size so outline and shadow still have a silhouette.
    override fun toModifierBitmap(size: Int): Bitmap =
        maskToLauncherShape(render(size, ADAPTIVE_ICON_SCALE))
    override fun previewBitmap(): Bitmap = previewBitmap(PREVIEW_SIZE)
    override fun previewBitmap(sizePx: Int): Bitmap {
        require(sizePx > 0)
        return render(sizePx.coerceAtMost(PREVIEW_SIZE), ADAPTIVE_ICON_SCALE)
    }
    override fun toBrowserPreviewBitmap(): Bitmap = previewBitmap()
    override fun toBrowserPreviewBitmap(sizePx: Int): Bitmap = previewBitmap(sizePx)
    override fun toDbString(): String = AdaptiveIconPayload.encode(this)
    override fun getIntrinsicWidth(): Int = PREVIEW_SIZE
    override fun getIntrinsicHeight(): Int = PREVIEW_SIZE

    @Composable
    override fun getPainter(): Painter = rememberPreviewPainter(PREVIEW_SIZE)

    override fun draw(canvas: Canvas) {
        val size = maxOf(bounds.width(), bounds.height()).coerceAtMost(PREVIEW_SIZE)
        if (size <= 0) return
        val bitmap = IconPreviewCache.getOrRender(IconPreviewCacheKey(this, size)) ?: return
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // A null monochrome keeps the current layer: only transforms that move the foreground pass one.
    fun withForeground(foreground: Bitmap, monochrome: Bitmap? = null): AdaptiveIconPackDrawable =
        AdaptiveIconPackDrawable(
            encodeLayer(foreground),
            backgroundPng,
            monochrome?.let(::encodeLayer) ?: monochromePng,
            materialYouEditState,
            originalForegroundPng,
            originalBackgroundPng
        )

    fun withMaterialYouLayers(
        foreground: Bitmap,
        background: Bitmap,
        state: MaterialYouPackEditState,
        originalForegroundPng: ByteArray? = this.originalForegroundPng ?: foregroundPng,
        originalBackgroundPng: ByteArray? = this.originalBackgroundPng ?: backgroundPng
    ): AdaptiveIconPackDrawable = AdaptiveIconPackDrawable(
        encodeLayer(foreground),
        encodeLayer(background),
        monochromePng,
        state,
        originalForegroundPng,
        originalBackgroundPng
    )

    fun restoreOriginalMaterialYouLayers(state: MaterialYouPackEditState): AdaptiveIconPackDrawable {
        val foreground = originalForegroundPng ?: foregroundPng
        val background = originalBackgroundPng ?: backgroundPng
        return AdaptiveIconPackDrawable(
            foreground, background, monochromePng, state,
            originalForegroundPng = null, originalBackgroundPng = null
        )
    }

    private fun render(size: Int, previewScale: Float = 1f): Bitmap = newArgbBitmap(size, size) { canvas ->
        // Match the launcher's 108/72 viewport without allocating a second full-size preview.
        if (previewScale != 1f) canvas.scale(previewScale, previewScale, size / 2f, size / 2f)
        val layerSize = (size * ADAPTIVE_ICON_SCALE * previewScale).toInt().coerceAtLeast(1)
        val renderedBackground = decodeLayer(backgroundPng, layerSize)
        val renderedForeground = decodeLayer(foregroundPng, layerSize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AdaptiveIconDrawable(layerDrawable(renderedBackground), layerDrawable(renderedForeground)).apply {
                setBounds(0, 0, size, size)
                draw(canvas)
            }
        } else {
            val extent = size.toFloat()
            canvas.clipPath(Path().apply { addOval(0f, 0f, extent, extent, Path.Direction.CW) })
            val inset = extent * (ADAPTIVE_ICON_SCALE - 1f) / 2f
            val target = RectF(-inset, -inset, extent + inset, extent + inset)
            canvas.drawBitmap(renderedBackground, null, target, paint)
            canvas.drawBitmap(renderedForeground, null, target, paint)
        }
    }

    private fun maskToLauncherShape(artwork: Bitmap): Bitmap {
        val size = artwork.width
        val mask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AdaptiveIconDrawable(null, null).apply { setBounds(0, 0, size, size) }.iconMask
        } else {
            Path().apply { addOval(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW) }
        }
        return newArgbBitmap(size, artwork.height) { canvas ->
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawPath(mask, maskPaint)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(artwork, 0f, 0f, maskPaint)
        }
    }

    companion object {
        const val PREVIEW_SIZE = 500
        const val LAYER_SIZE = 750

        @RequiresApi(Build.VERSION_CODES.O)
        fun capture(
            icon: AdaptiveIconDrawable,
            monochrome: Drawable? = null,
            materialYouEditState: MaterialYouPackEditState? = null,
            originalForegroundPng: ByteArray? = null,
            originalBackgroundPng: ByteArray? = null
        ): AdaptiveIconPackDrawable =
            AdaptiveIconPackDrawable(
                captureLayer(icon.foreground),
                captureLayer(icon.background),
                monochrome?.let(::captureLayer),
                materialYouEditState,
                originalForegroundPng,
                originalBackgroundPng
            )

        // Capture the whole 108dp layer, including its own inset, before Android applies 72dp masking.
        fun captureLayer(layer: Drawable): Bitmap {
            val oldBounds = Rect(layer.bounds)
            return try {
                newArgbBitmap(LAYER_SIZE, LAYER_SIZE) { canvas ->
                    layer.setBounds(0, 0, LAYER_SIZE, LAYER_SIZE)
                    layer.draw(canvas)
                }.apply { density = Bitmap.DENSITY_NONE }
            } finally {
                layer.bounds = oldBounds
            }
        }

        private fun layerDrawable(bitmap: Bitmap): BitmapDrawable = BitmapDrawable(null, bitmap).apply {
            gravity = Gravity.FILL
            isFilterBitmap = true
        }

        private fun encodeLayer(layer: Bitmap): ByteArray = layer.getBytes(Bitmap.CompressFormat.PNG, 100)

        // Payloads are only header-checked on load (a full decode per layer made startup slow), so a
        // damaged layer renders as transparent instead of crashing the list.
        private fun decodeLayer(png: ByteArray, targetSize: Int = LAYER_SIZE): Bitmap {
            val options = BitmapFactory.Options()
            if (targetSize < LAYER_SIZE) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(png, 0, png.size, options)
                options.inJustDecodeBounds = false
                var sample = 1
                while (minOf(options.outWidth, options.outHeight) / (sample * 2) >= targetSize) {
                    sample *= 2
                }
                options.inSampleSize = sample
            }
            val layer = BitmapFactory.decodeByteArray(png, 0, png.size, options)
                ?: Bitmap.createBitmap(
                    targetSize.coerceAtMost(LAYER_SIZE), targetSize.coerceAtMost(LAYER_SIZE), Bitmap.Config.ARGB_8888
                )
            return layer.apply { density = Bitmap.DENSITY_NONE }
        }
    }
}
