package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
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

internal fun getBitmapFromURI(context: Context, uri: Uri): Bitmap? {
    val contentResolver = context.contentResolver

    var bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }

    if (bitmap == null) {
        val svg = contentResolver.openInputStream(uri).use { decodeSVGSteam(it) }

        if (svg != null) {
            if (svg.documentWidth > 0 && svg.documentHeight > 0) {
                bitmap = Bitmap.createBitmap(svg.documentWidth.toInt(), svg.documentHeight.toInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                svg.renderToCanvas(canvas)
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

    val zoomedImage = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val mtx = Matrix()
    mtx.postScale(zoomLevel, zoomLevel)
    mtx.postTranslate(x, y)

    val canvas = Canvas(zoomedImage)
    canvas.drawBitmap(image, mtx, Paint())

    return zoomedImage
}

internal fun squareBitmap(image: Bitmap): Bitmap {
    if (image.width == image.height) {
        return image
    }

    val size = max(image.width, image.height)
    val squaredImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    val x = (size - image.width) / 2f
    val y = (size - image.height) / 2f

    val mtx = Matrix()
    mtx.postTranslate(x, y)

    val canvas = Canvas(squaredImage)
    canvas.drawBitmap(image, mtx, Paint())

    return squaredImage
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

    val mask = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val maskCanvas = Canvas(mask)
    maskCanvas.drawPath(path.asAndroidPath(), paint)

    return mask
}
