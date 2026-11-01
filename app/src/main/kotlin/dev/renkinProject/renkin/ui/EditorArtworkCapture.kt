package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.MainThread
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import dev.renkinProject.renkin.data.editor.*
import dev.renkinProject.renkin.drawable.*
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import dev.renkinProject.renkin.vector.brush.SolidColorShader
import java.nio.ByteBuffer

/** Copy on the editor's owning thread; encode only the detached result off Main. */
@MainThread
internal fun captureEditorArtwork(icon: IconPackDrawable): EditorArtworkSnapshot =
    captureArtwork(icon, EditorArtworkBudget(), 0)

private fun captureArtwork(icon: IconPackDrawable, budget: EditorArtworkBudget, depth: Int): EditorArtworkSnapshot {
    budget.node(depth)
    return when (icon) {
        is BitmapIconDrawable -> {
            val bitmap = icon.toBitmap()
            require(!bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888 &&
                (Build.VERSION.SDK_INT < 26 || bitmap.colorSpace?.isSrgb == true)) { "Unsupported editor bitmap configuration" }
            require(bitmap.width in 1..4096 && bitmap.height in 1..4096)
            budget.bytes(bitmap.byteCount)
            val pixels = ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer).array()
            EditorBitmapArtwork(bitmap.width, bitmap.height, bitmap.rowBytes, bitmap.density, bitmap.hasAlpha(),
                bitmap.isPremultiplied, icon.isAdaptiveIcon(), icon.previewScale, EditorArtworkBytes(pixels))
        }
        is ImageVectorDrawable -> EditorVectorArtwork(icon.name, icon.defaultWidth.value, icon.defaultHeight.value,
            icon.viewportWidth, icon.viewportHeight, icon.tintColor.value.toLong(), icon.tintBlendMode.toString(),
            icon.autoMirror, icon.scaleStrokesWithBounds, captureGroup(icon.root, budget, depth + 1))
        is AdaptiveIconPackDrawable -> {
            fun layer(bytes: ByteArray): EditorArtworkBytes { budget.bytes(bytes.size); return EditorArtworkBytes(bytes) }
            val state = icon.materialYouEditState
            EditorAdaptiveArtwork(layer(icon.foregroundPng), layer(icon.backgroundPng), icon.monochromePng?.let(::layer),
                state?.let { EditorPackControls(it.selectedScheme, it.customForeground.detached(), it.customBackground.detached(), it.strokeScale) },
                icon.originalForegroundPng?.let(::layer), icon.originalBackgroundPng?.let(::layer))
        }
        is InsetIconDrawable -> {
            val child = icon.drawable as? IconPackDrawable ?: throw IllegalArgumentException("Unsupported editor inset child")
            EditorInsetArtwork(captureArtwork(child, budget, depth + 1), icon.dimensions.left, icon.dimensions.top,
                icon.dimensions.right, icon.dimensions.bottom, icon.fractions.left, icon.fractions.top, icon.fractions.right, icon.fractions.bottom)
        }
        else -> throw IllegalArgumentException("Unsupported editor artwork type")
    }
}

private fun captureGroup(group: MutableVectorGroup, budget: EditorArtworkBudget, depth: Int): EditorVectorGroup {
    budget.node(depth); budget.commands(group.clipPathData.size)
    require(group.children.size <= EditorArtworkBudget.MAX_NODES)
    return EditorVectorGroup(group.name, group.rotation, group.pivotX, group.pivotY, group.scaleX, group.scaleY,
        group.translationX, group.translationY, group.clipPathData.toList(), group.children.map {
            when (it) {
                is MutableVectorGroup -> captureGroup(it, budget, depth + 1)
                is MutableVectorPath -> capturePath(it, budget, depth + 1)
            }
        })
}

private fun capturePath(path: MutableVectorPath, budget: EditorArtworkBudget, depth: Int = 0): EditorVectorPath {
    budget.node(depth); budget.commands(path.pathData.size)
    return EditorVectorPath(path.name, path.pathData.toList(), path.pathFillType == PathFillType.EvenOdd,
        captureBrush(path.fill), path.fillAlpha, captureBrush(path.stroke), path.strokeAlpha, path.strokeLineWidth,
        path.strokeLineCap.toString(), path.strokeLineJoin.toString(), path.strokeLineMiter,
        path.trimPathStart, path.trimPathEnd, path.trimPathOffset)
}

private fun captureBrush(brush: Brush?): EditorVectorBrush? = when (brush) {
    null -> null
    is SolidColor -> EditorVectorBrush(brush.value.value.toLong())
    is ReferenceBrush -> {
        val fallback = brush.shaderBrush as? SolidColorShader ?: throw IllegalArgumentException("Unsupported reference shader")
        EditorVectorBrush(fallback.color.value.toLong(), brush.reference)
    }
    else -> throw IllegalArgumentException("Unsupported editor vector brush")
}

private fun ColorizerStyle.detached(): ColorizerStyle {
    require(gradientStops.size <= EditorControlsCodec.MAX_STYLE_VALUES && gradientPositions.size <= EditorControlsCodec.MAX_STYLE_VALUES)
    return copy(gradientStops = gradientStops.toList(), gradientPositions = gradientPositions.toList())
}

@MainThread
internal fun VectorEditState.captureArtworkState(): EditorVectorWorkingState {
    val budget = EditorArtworkBudget()
    fun capture(value: VectorEditState.Inputs): EditorVectorInputs {
        require(value.entries.size <= EditorArtworkBudget.MAX_NODES)
        val entries = value.entries.map {
            val path = it.path
            budget.node(0); budget.commands(path.pathData.size)
            EditorVectorEntry(EditorVectorPath(path.name, path.pathData.toList(), path.pathFillType == PathFillType.EvenOdd,
                captureBrush(path.fill), path.fillAlpha, captureBrush(path.stroke), path.strokeAlpha, path.strokeLineWidth,
                path.strokeLineCap.toString(), path.strokeLineJoin.toString(), path.strokeLineMiter,
                path.trimPathStart, path.trimPathEnd, path.trimPathOffset), it.filled, it.baseStroke)
        }
        return EditorVectorInputs(entries, value.thickness, value.automaticallyCenter,
            value.viewport?.let { EditorViewport(it.first, it.second) }, value.sourceUrl, value.containsBakedModifierEdits)
    }
    val current = capture(inputs())
    val seed = seedForDraft()?.let(::capture)
    require(!initialized || seed != null) { "Initialized editor vector has no seed" }
    return EditorVectorWorkingState(initialized, current, seed)
}
