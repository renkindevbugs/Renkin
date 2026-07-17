package dev.renkinProject.renkin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import dev.renkinProject.renkin.extension.newArgbBitmap
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.vector.SvgRasterizer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

// Bitmap/SVG helpers shared by UploadColumn (UploadGallery.kt). internal so the upload
// composables in the same module can call them after the split.

// Imported images end up as icons, so anything bigger than this per side is wasted RAM — a 48 MP
// camera photo would otherwise materialise as a ~190 MB ARGB_8888 bitmap and risk an OOM.
private const val MAX_IMPORT_SIZE = 1024
internal const val MAX_TEXT_IMPORT_BYTES = 5 * 1024 * 1024

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
            contentResolver.openInputStream(uri)?.use { it.readUtf8TextLimited() }
        }.getOrNull()
        if (markup != null && markup.contains("<svg", ignoreCase = true)) {
            bitmap = decodeSvgToBitmap(markup)
        }
    }

    return bitmap ?: null
}

/** Reads text imports without allowing a malformed/renamed file to consume unbounded memory. */
internal fun InputStream.readUtf8TextLimited(
    maxBytes: Int = MAX_TEXT_IMPORT_BYTES
): String? {
    if (maxBytes < 0) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

/**
 * Renders SVG markup to a bitmap at the full import size (scaling in SvgRasterizer). A render
 * that painted nothing at all reports failure (error toast) instead of leaving a blank tile
 * in the gallery.
 */
internal fun decodeSvgToBitmap(markup: String): Bitmap? {
    val bitmap = SvgRasterizer.rasterize(markup, MAX_IMPORT_SIZE) ?: return null
    return if (bitmap.hasAnyVisiblePixel()) bitmap else null
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
