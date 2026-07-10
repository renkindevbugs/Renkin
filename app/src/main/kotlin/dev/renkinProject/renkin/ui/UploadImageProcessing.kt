package dev.renkinProject.renkin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import dev.renkinProject.renkin.extension.newArgbBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import kotlin.math.max

// Bitmap/SVG helpers shared by UploadColumn (UploadGallery.kt). internal so the upload
// composables in the same module can call them after the split.

// Imported images end up as icons, so anything bigger than this per side is wasted RAM — a 48 MP
// camera photo would otherwise materialise as a ~190 MB ARGB_8888 bitmap and risk an OOM.
private const val MAX_IMPORT_SIZE = 1024

internal fun getBitmapFromURI(context: Context, uri: Uri): Bitmap? {
    val contentResolver = context.contentResolver

    // Bounds-only pass first, so a huge photo is downsampled during decode instead of after it.
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, boundsOptions) }
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = 1
        // Cap the LARGEST side: with the textbook both-sides condition a 20000x500 panorama
        // never downsamples (the short side bails the loop out) and allocates tens of MB.
        while (max(boundsOptions.outWidth, boundsOptions.outHeight) / (inSampleSize * 2) >= MAX_IMPORT_SIZE) {
            inSampleSize *= 2
        }
    }

    var bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, decodeOptions) }

    if (bitmap == null) {
        // Not a raster image — try SVG. Reading as text is cheap here: this branch is only
        // reached when the raster decode already failed, and real SVGs are small.
        val markup = runCatching {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        if (markup != null && markup.contains("<svg", ignoreCase = true)) {
            bitmap = decodeSvgToBitmap(markup)
        }
    }

    return bitmap ?: null
}

/**
 * Renders SVG markup to a bitmap at the full import size. An SVG's document size is a hint,
 * not pixels — so the scale goes BOTH ways: a 24x24 icon document (heroicons etc.) rendered
 * 1:1 and enlarged later is exactly the blur vectors exist to avoid. A render that painted
 * nothing at all reports failure (error toast) instead of leaving a blank tile in the gallery.
 */
internal fun decodeSvgToBitmap(markup: String): Bitmap? {
    val svg = decodeSvg(markup) ?: return null
    val (width, height) = svgRenderSize(svg) ?: return null
    val bitmap = newArgbBitmap(width, height) {
        svg.renderToCanvas(it, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }
    return if (bitmap.hasAnyVisiblePixel()) bitmap else null
}

/** Parses SVG markup, resolving `currentColor` to black up front — icon sets use it
 * throughout, and unresolved it draws nothing (a blank import). */
internal fun decodeSvg(markup: String): SVG? = try {
    SVG.getFromString(markup.replace("currentColor", "#000000"))
} catch (_: SVGParseException) {
    null
}

/** Raster size for [svg]: the longest side always lands on MAX_IMPORT_SIZE (up or down);
 * documents without width/height fall back to their viewBox. */
internal fun svgRenderSize(svg: SVG): Pair<Int, Int>? {
    val docWidth = if (svg.documentWidth > 0) svg.documentWidth else svg.documentViewBox?.width() ?: 0f
    val docHeight = if (svg.documentHeight > 0) svg.documentHeight else svg.documentViewBox?.height() ?: 0f
    if (docWidth <= 0 || docHeight <= 0) return null
    val scale = MAX_IMPORT_SIZE / max(docWidth, docHeight)
    val width = (docWidth * scale).toInt().coerceAtLeast(1)
    val height = (docHeight * scale).toInt().coerceAtLeast(1)
    return width to height
}

private fun Bitmap.hasAnyVisiblePixel(): Boolean {
    val step = (max(width, height) / 64).coerceAtLeast(1)
    for (x in 0 until width step step) {
        for (y in 0 until height step step) {
            if ((getPixel(x, y) ushr 24) != 0) return true
        }
    }
    return false
}

@Composable
internal fun zoomBitmap(image: Bitmap, zoomLevel: Float): Bitmap {
    if (zoomLevel == 1f) {
        return image
    }

    val x = (image.width - (image.width * zoomLevel)) / 2
    val y = (image.height - (image.height * zoomLevel)) / 2

    val mtx = Matrix()
    mtx.postScale(zoomLevel, zoomLevel)
    mtx.postTranslate(x, y)

    return newArgbBitmap(image.width, image.height) { it.drawBitmap(image, mtx, Paint()) }
}

internal fun squareBitmap(image: Bitmap): Bitmap {
    if (image.width == image.height) {
        return image
    }

    val size = max(image.width, image.height)
    val x = (size - image.width) / 2f
    val y = (size - image.height) / 2f

    val mtx = Matrix()
    mtx.postTranslate(x, y)

    return newArgbBitmap(size, size) { it.drawBitmap(image, mtx, Paint()) }
}

internal fun createMask(image: Bitmap): Bitmap {
    val startActiveZone = image.width / 6f
    val topActiveZone = image.height / 6f
    val endActiveZone = image.width - startActiveZone
    val bottomActiveZone = image.height - topActiveZone

    val path = Path()
    path.moveTo(0f, 0f)
    path.lineTo(image.width.toFloat(), 0f)
    path.lineTo(image.width.toFloat(), image.height.toFloat())
    path.lineTo(0f, image.height.toFloat())
    path.close()

    path.moveTo(startActiveZone, topActiveZone)
    path.lineTo(startActiveZone, bottomActiveZone)
    path.lineTo(endActiveZone, bottomActiveZone)
    path.lineTo(endActiveZone, topActiveZone)
    path.close()

    val paint = Paint()
    paint.color = Red.toArgb()
    paint.style = Paint.Style.FILL

    return newArgbBitmap(image.width, image.height) { it.drawPath(path.asAndroidPath(), paint) }
}
