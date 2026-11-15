package dev.renkinProject.renkin.drawable

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.icon.creator.MATERIAL_YOU_SCHEME_COLOR_IDS
import dev.renkinProject.renkin.icon.parser.ThemedGlyphPayload

/**
 * A themed icon: [mask] is a one-colour glyph on the 108dp adaptive layer. The pack exports it as
 * a mask the launcher tints with the wallpaper accent, over a wallpaper-coloured background, so
 * the icon follows wallpaper and dark-mode changes. Renkin previews it in the current colours.
 * [scheme] picks the colours: [SYSTEM_PALETTE] for the themed-icon pair that follows dark mode,
 * or the index of a Material You scheme the app-icon variant offers, which keeps that pair.
 */
class ThemedGlyphDrawable(mask: Bitmap, val scheme: Int = SYSTEM_PALETTE) : IconPackDrawable() {
    private val glyph = BitmapIconDrawable(mask)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** The glyph itself, shared with this drawable: read it, never recycle or mutate it. */
    val mask: Bitmap get() = glyph.toBitmap()

    fun withMask(mask: Bitmap): ThemedGlyphDrawable = ThemedGlyphDrawable(mask, scheme)

    override fun isAdaptiveIcon(): Boolean = true
    override fun toBitmap(): Bitmap = render(PREVIEW_SIZE)
    override fun toModifierBitmap(size: Int): Bitmap = render(size)
    override fun previewBitmap(): Bitmap = render(PREVIEW_SIZE)
    override fun previewBitmap(sizePx: Int): Bitmap {
        require(sizePx > 0)
        return render(sizePx.coerceAtMost(PREVIEW_SIZE))
    }
    override fun toDbString(): String = ThemedGlyphPayload.encode(this)
    override fun getIntrinsicWidth(): Int = PREVIEW_SIZE
    override fun getIntrinsicHeight(): Int = PREVIEW_SIZE

    @Composable
    override fun getPainter(): Painter {
        val configuration = LocalConfiguration.current
        val revision = remember(configuration, scheme) { colorRevision() }
        return rememberPreviewPainter(PREVIEW_SIZE, revision = revision)
    }

    override fun draw(canvas: Canvas) {
        val size = maxOf(bounds.width(), bounds.height()).coerceAtMost(PREVIEW_SIZE)
        if (size <= 0) return
        val bitmap = IconPreviewCache.getOrRender(IconPreviewCacheKey(this, size, colorRevision())) ?: return
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // The preview cache keys on identity, so the render colours must be part of the key.
    private fun colorRevision(): Int = ThemedGlyphColors.current(scheme).hashCode()

    private fun render(size: Int): Bitmap {
        val (foreground, background) = ThemedGlyphColors.current(scheme)
        val layerSize = (size * ADAPTIVE_ICON_SCALE).toInt().coerceAtLeast(1)
        val tinted = newArgbBitmap(layerSize, layerSize) { canvas ->
            canvas.drawBitmap(mask, null, Rect(0, 0, layerSize, layerSize), Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
            })
        }
        val rendered = newArgbBitmap(size, size) { canvas ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val layer = BitmapDrawable(null, tinted).apply {
                    gravity = Gravity.FILL
                    isFilterBitmap = true
                }
                AdaptiveIconDrawable(ColorDrawable(background), layer).apply {
                    setBounds(0, 0, size, size)
                    draw(canvas)
                }
            } else {
                canvas.drawColor(background)
                val inset = size * (ADAPTIVE_ICON_SCALE - 1f) / 2f
                canvas.drawBitmap(tinted, null, RectF(-inset, -inset, size + inset, size + inset), paint)
            }
        }
        tinted.recycle()
        return rendered
    }

    companion object {
        const val PREVIEW_SIZE = 500
        /** Masks are stored at this size: sharp on any launcher, small enough for the database. */
        const val MASK_SIZE = 500
        // The themed-icon colours that follow dark mode (icon_color and icon_background_color in the pack).
        const val SYSTEM_PALETTE = -1
    }
}

/**
 * The colours the launcher gives a themed icon right now: the pairs IconPackBuilder writes into
 * the pack, resolved from the system the way the launcher resolves them.
 */
internal object ThemedGlyphColors {
    fun current(scheme: Int = ThemedGlyphDrawable.SYSTEM_PALETTE): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Color.WHITE to Color.BLACK
        val system = Resources.getSystem()
        MATERIAL_YOU_SCHEME_COLOR_IDS.getOrNull(scheme)?.let { (foreground, background) ->
            return system.getColor(foreground, null) to system.getColor(background, null)
        }
        val night = (system.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val foreground = if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_700
        val background = if (night) android.R.color.system_accent2_800 else android.R.color.system_accent1_100
        return system.getColor(foreground, null) to system.getColor(background, null)
    }
}
