package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import dev.alembiconsProject.alembicons.extension.newArgbBitmap
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
                bitmap = newArgbBitmap(svg.documentWidth.toInt(), svg.documentHeight.toInt()) { svg.renderToCanvas(it) }
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
