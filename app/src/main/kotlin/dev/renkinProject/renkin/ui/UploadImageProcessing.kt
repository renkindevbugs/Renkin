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
import java.io.InputStream
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
        val svg = contentResolver.openInputStream(uri).use { decodeSVGSteam(it) }

        if (svg != null) {
            // An SVG's document size is a hint, not pixels. Render at the full import size in
            // BOTH directions: scaling up matters just as much — a 24x24 icon document (e.g.
            // heroicons) rendered 1:1 and enlarged later is exactly the blur vectors avoid.
            // Documents that declare no width/height at all fall back to their viewBox.
            val docWidth = if (svg.documentWidth > 0) svg.documentWidth else svg.documentViewBox?.width() ?: 0f
            val docHeight = if (svg.documentHeight > 0) svg.documentHeight else svg.documentViewBox?.height() ?: 0f
            if (docWidth > 0 && docHeight > 0) {
                val scale = MAX_IMPORT_SIZE / max(docWidth, docHeight)
                val width = (docWidth * scale).toInt().coerceAtLeast(1)
                val height = (docHeight * scale).toInt().coerceAtLeast(1)
                bitmap = newArgbBitmap(width, height) {
                    svg.renderToCanvas(it, RectF(0f, 0f, width.toFloat(), height.toFloat()))
                }
            }
        }
    }

    return bitmap ?: null
}

internal fun decodeSVGSteam(stream: InputStream?): SVG? {
    if (stream == null)
        return null

    return try {
        SVG.getFromInputStream(stream)
    } catch (_: SVGParseException) {
        null
    }
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
