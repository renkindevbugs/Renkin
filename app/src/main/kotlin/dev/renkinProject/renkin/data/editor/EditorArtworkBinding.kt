package dev.renkinProject.renkin.data.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.*
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Native decoding/binding is off Main, after the owner verifies artifact checksums and ownership. */
internal suspend fun restoreEditorArtwork(value: EditorArtworkSnapshot): IconPackDrawable = withContext(Dispatchers.Default) {
    val owner = currentCoroutineContext()
    // Validate the entire detached tree and its aggregate budgets before allocating native artwork.
    EditorArtworkCodec.encode(value)
    fun restore(value: EditorArtworkSnapshot): IconPackDrawable {
        owner.ensureActive()
        return when (value) {
            is EditorBitmapArtwork -> {
                EditorArtworkCodec.validateBitmap(value)
                val bitmap = Bitmap.createBitmap(value.width, value.height, Bitmap.Config.ARGB_8888)
                try {
                    require(bitmap.rowBytes == value.rowBytes) { "Editor bitmap layout is incompatible with this runtime" }
                    bitmap.density = value.density; bitmap.setHasAlpha(value.hasAlpha); bitmap.setPremultiplied(value.premultiplied)
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(value.pixels.copyBytes()))
                    require(bitmap.hasAlpha() == value.hasAlpha && bitmap.isPremultiplied == value.premultiplied) {
                        "Editor bitmap flags are incompatible with this runtime"
                    }
                    owner.ensureActive()
                    BitmapIconDrawable(bitmap, value.adaptive, value.previewScale)
                } catch (error: Exception) { bitmap.recycle(); throw error }
            }
            is EditorVectorArtwork -> {
                val builder = ImageVector.Builder(value.name, value.width.dp, value.height.dp, value.viewportWidth, value.viewportHeight,
                    artworkColor(value.tintBits), artworkBlend(value.tintBlendMode), value.autoMirror)
                builder.addArtworkGroup(value.root)
                val vector = builder.build()
                ImageVectorDrawable(vector, value.scaleStrokesWithBounds).apply {
                    root = MutableVectorGroup(vector.root[0] as VectorGroup)
                }
            }
            is EditorAdaptiveArtwork -> {
                fun layer(bytes: EditorArtworkBytes): ByteArray {
                    owner.ensureActive()
                    val png = bytes.copyBytes()
                    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
                    require(png.size >= signature.size && signature.indices.all { png[it] == signature[it] }) { "Invalid adaptive PNG" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                    require(bounds.outWidth == AdaptiveIconPackDrawable.LAYER_SIZE && bounds.outHeight == bounds.outWidth) { "Invalid adaptive layer dimensions" }
                    val decoded = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "Invalid adaptive pixels" }
                    decoded.recycle(); owner.ensureActive(); return png
                }
                val state = value.controls?.let {
                    require(it.selectedScheme >= -1 && it.strokeScale in 0.5f..2f) { "Invalid adaptive edit state" }
                    MaterialYouPackEditState(it.selectedScheme, it.customForeground, it.customBackground, it.strokeScale)
                }
                AdaptiveIconPackDrawable(layer(value.foreground), layer(value.background), value.monochrome?.let(::layer), state,
                    value.originalForeground?.let(::layer), value.originalBackground?.let(::layer))
            }
            is EditorInsetArtwork -> InsetIconDrawable(restore(value.child), Rect(value.left, value.top, value.right, value.bottom),
                RectF(value.fractionLeft, value.fractionTop, value.fractionRight, value.fractionBottom))
        }
    }
    restore(value).also { owner.ensureActive() }
}

internal fun EditorVectorPath.toDraftVectorPath(): VectorPath {
    val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    builder.addArtworkPath(this)
    return builder.build().root[0] as VectorPath
}

private fun ImageVector.Builder.addArtworkGroup(group: EditorVectorGroup) {
    addGroup(group.name, group.rotation, group.pivotX, group.pivotY, group.scaleX, group.scaleY, group.translationX, group.translationY, group.clip)
    for (child in group.children) when (child) {
        is EditorVectorGroup -> addArtworkGroup(child)
        is EditorVectorPath -> addArtworkPath(child)
    }
    clearGroup()
}

private fun ImageVector.Builder.addArtworkPath(path: EditorVectorPath) {
    fun brush(value: EditorVectorBrush?): Brush? = value?.let {
        val color = artworkColor(it.colorBits)
        if (it.reference == null) SolidColor(color) else ReferenceBrush(it.reference, color)
    }
    val cap = when (path.cap) {
        "Butt" -> StrokeCap.Butt
        "Round" -> StrokeCap.Round
        "Square" -> StrokeCap.Square
        else -> throw IllegalArgumentException("Unknown editor stroke cap")
    }
    val join = when (path.join) {
        "Miter" -> StrokeJoin.Miter
        "Round" -> StrokeJoin.Round
        "Bevel" -> StrokeJoin.Bevel
        else -> throw IllegalArgumentException("Unknown editor stroke join")
    }
    addPath(path.commands, if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero, path.name,
        brush(path.fill), path.fillAlpha, brush(path.stroke), path.strokeAlpha, path.strokeWidth, cap, join, path.miter,
        path.trimStart, path.trimEnd, path.trimOffset)
}

private fun artworkColor(bits: Long): Color = Color(bits.toULong()).also {
    if (it != Color.Unspecified) {
        it.colorSpace
        require(listOf(it.red, it.green, it.blue, it.alpha).all(Float::isFinite)) { "Invalid editor color" }
    }
}

private fun artworkBlend(name: String): BlendMode = requireNotNull(listOf(
    BlendMode.Clear, BlendMode.Src, BlendMode.Dst, BlendMode.SrcOver, BlendMode.DstOver, BlendMode.SrcIn, BlendMode.DstIn,
    BlendMode.SrcOut, BlendMode.DstOut, BlendMode.SrcAtop, BlendMode.DstAtop, BlendMode.Xor, BlendMode.Plus, BlendMode.Modulate,
    BlendMode.Screen, BlendMode.Overlay, BlendMode.Darken, BlendMode.Lighten, BlendMode.ColorDodge, BlendMode.ColorBurn,
    BlendMode.Hardlight, BlendMode.Softlight, BlendMode.Difference, BlendMode.Exclusion, BlendMode.Multiply,
    BlendMode.Hue, BlendMode.Saturation, BlendMode.Color, BlendMode.Luminosity
).firstOrNull { it.toString() == name }) { "Unknown editor tint blend mode" }
