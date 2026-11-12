package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal enum class EditorMode(val durationMillis: Int) {
    CREATE(320), UPLOAD(380), VECTOR(420), MODIFIER(420)
}

@Composable
internal fun EditorModeIcon(
    mode: EditorMode,
    selected: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    val progress = remember(mode) { Animatable(1f) }
    var previousSelected by remember(mode) { mutableStateOf(selected) }
    val fill = animateFloatAsState(
        targetValue = if (selected) 0.17f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(200),
        label = "editorModeFill"
    )
    val curve = remember { Path() }
    val nodeMask = remember { Path() }

    LaunchedEffect(mode, selected, reduceMotion) {
        val changed = previousSelected != selected
        previousSelected = selected
        if (reduceMotion || !changed) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(mode.durationMillis, easing = LinearEasing))
        }
    }

    Canvas(modifier.size(24.dp)) {
        val phase = if (reduceMotion) 1f else progress.value
        val fillAlpha = if (reduceMotion) {
            if (selected) 0.17f else 0f
        } else fill.value
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            when (mode) {
                EditorMode.CREATE -> drawCreateIcon(phase, selected, fillAlpha, tint)
                EditorMode.UPLOAD -> drawUploadIcon(phase, fillAlpha, tint)
                EditorMode.VECTOR -> drawVectorIcon(phase, fillAlpha, tint, curve, nodeMask)
                EditorMode.MODIFIER -> drawModifierIcon(phase, tint)
            }
        }
    }
}

private val ModeIconEasing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)
private val ModeIconStroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val VectorHandleStroke = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ModifierRailStroke = Stroke(width = 2f, cap = StrokeCap.Butt)
private val ModifierHeadStroke = Stroke(width = 2.2f, cap = StrokeCap.Butt)

private object ModeIconPaths {
    val createFrame = path("M7.5 3.5h9a4 4 0 0 1 4 4v9a4 4 0 0 1-4 4h-9a4 4 0 0 1-4-4v-9a4 4 0 0 1 4-4z")
    val createPlus = path("M8 12h8M12 8v8")
    val uploadFrame = path("M14 3.5H6A2.5 2.5 0 0 0 3.5 6v12A2.5 2.5 0 0 0 6 20.5h12a2.5 2.5 0 0 0 2.5-2.5v-7")
    val uploadMountains = path("M4 17l5-5 4 4 3-3 4 4")
    val uploadPlus = path("M19 2v6M16 5h6")
    val vectorNib = path("M12 8l5.5 9-2 5h-7l-2-5z")
    val vectorNibStem = path("M12 8v7.5")
    val vectorEndNodes = path("M1.8 10.5h2.4a.3.3 0 0 1 .3.3v2.4a.3.3 0 0 1-.3.3H1.8a.3.3 0 0 1-.3-.3v-2.4a.3.3 0 0 1 .3-.3zM19.8 10.5h2.4a.3.3 0 0 1 .3.3v2.4a.3.3 0 0 1-.3.3h-2.4a.3.3 0 0 1-.3-.3v-2.4a.3.3 0 0 1 .3-.3z")
    val vectorTopNode = path("M10.8 2.5h2.4a.3.3 0 0 1 .3.3v2.4a.3.3 0 0 1-.3.3h-2.4a.3.3 0 0 1-.3-.3V2.8a.3.3 0 0 1 .3-.3z")
    val vectorTangent = path("M3 4h7.5M13.5 4H21")
    val modifierRails = path("M3 6h18M3 12h18M3 18h18")
    val modifierHeads = listOf(path("M16 3v6"), path("M8 9v6"), path("M12 15v6"))

    private fun path(data: String): Path = PathParser().parsePathString(data).toPath()
}

private fun DrawScope.drawOutlinedPath(path: Path, tint: Color, fillAlpha: Float = 0f) {
    if (fillAlpha > 0f) drawPath(path, tint, alpha = fillAlpha)
    drawPath(path, tint, style = ModeIconStroke)
}

private fun DrawScope.drawCreateIcon(phase: Float, selected: Boolean, fillAlpha: Float, tint: Color) {
    val eased = ModeIconEasing.transform(phase)
    val frameScale = 0.94f + 0.06f * eased
    scale(frameScale, frameScale, pivot = Offset(12f, 12f)) {
        drawOutlinedPath(ModeIconPaths.createFrame, tint, fillAlpha)
    }
    val plusScale = 0.65f + 0.35f * eased
    val angle = (if (selected) -90f else 90f) * (1f - eased)
    rotate(angle, pivot = Offset(12f, 12f)) {
        scale(plusScale, plusScale, pivot = Offset(12f, 12f)) {
            drawPath(ModeIconPaths.createPlus, tint, alpha = 0.25f + 0.75f * eased, style = ModeIconStroke)
        }
    }
}

private fun DrawScope.drawUploadIcon(phase: Float, fillAlpha: Float, tint: Color) {
    drawOutlinedPath(ModeIconPaths.uploadFrame, tint, fillAlpha)
    drawPath(ModeIconPaths.uploadMountains, tint, style = ModeIconStroke)
    val elapsed = phase * EditorMode.UPLOAD.durationMillis
    val sunProgress = ModeIconEasing.transform(((elapsed - 40f) / 340f).coerceIn(0f, 1f))
    drawCircle(
        tint,
        radius = 1.25f,
        center = Offset(8f, 8f + 2f * (1f - sunProgress)),
        alpha = 0.3f + 0.7f * sunProgress,
        style = ModeIconStroke
    )
    val plusProgress = ModeIconEasing.transform((elapsed / 340f).coerceIn(0f, 1f))
    val plusScale = if (plusProgress <= 0.65f) {
        0.6f + 0.52f * plusProgress / 0.65f
    } else {
        1.12f - 0.12f * (plusProgress - 0.65f) / 0.35f
    }
    scale(plusScale, plusScale, pivot = Offset(19f, 5f)) {
        drawPath(
            ModeIconPaths.uploadPlus,
            tint,
            alpha = (plusProgress / 0.65f).coerceAtMost(1f),
            style = ModeIconStroke
        )
    }
}

private fun DrawScope.drawVectorIcon(
    phase: Float,
    fillAlpha: Float,
    tint: Color,
    curve: Path,
    nodeMask: Path
) {
    val pulse = sin(PI * phase).toFloat()
    val top = 4f - 1.2f * pulse
    curve.reset()
    curve.moveTo(3f, 12f)
    curve.cubicTo(3f, 7.5f - 2f * pulse, 7f, top, 12f, top)
    curve.cubicTo(17f, top, 21f, 7.5f - 2f * pulse, 21f, 12f)
    // Leave the node interiors transparent instead of painting a theme-specific background.
    nodeMask.reset()
    nodeMask.addPath(ModeIconPaths.vectorEndNodes)
    nodeMask.addPath(ModeIconPaths.vectorTopNode, Offset(0f, -1.2f * pulse))
    clipPath(nodeMask, ClipOp.Difference) {
        drawPath(curve, tint, style = VectorHandleStroke)
        translate(top = -1.2f * pulse) {
            drawPath(ModeIconPaths.vectorTangent, tint, style = VectorHandleStroke)
        }
    }
    drawPath(ModeIconPaths.vectorEndNodes, tint, style = VectorHandleStroke)
    translate(top = -1.2f * pulse) {
        drawPath(ModeIconPaths.vectorTopNode, tint, style = VectorHandleStroke)
    }
    val eased = ModeIconEasing.transform(phase)
    val angle = if (eased <= 0.7f) -5f + 6f * eased / 0.7f else 1f - (eased - 0.7f) / 0.3f
    rotate(angle, pivot = Offset(12f, 15f)) {
        drawOutlinedPath(ModeIconPaths.vectorNib, tint, fillAlpha)
        drawPath(ModeIconPaths.vectorNibStem, tint, style = ModeIconStroke)
        drawCircle(tint, radius = 1.3f, center = Offset(12f, 17f), style = ModeIconStroke)
    }
}

private fun DrawScope.drawModifierIcon(phase: Float, tint: Color) {
    drawPath(ModeIconPaths.modifierRails, tint, style = ModifierRailStroke)
    val pulse = sin(PI * phase).toFloat()
    ModeIconPaths.modifierHeads.forEachIndexed { index, path ->
        val distance = when (index) { 0 -> -3f; 1 -> 3f; else -> -2f }
        translate(left = distance * pulse) {
            drawPath(path, tint, style = ModifierHeadStroke)
        }
    }
}
