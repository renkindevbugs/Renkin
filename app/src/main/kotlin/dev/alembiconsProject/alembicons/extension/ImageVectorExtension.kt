package dev.alembiconsProject.alembicons.extension

import android.content.res.Resources
import android.content.res.XmlResourceParser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res2.vectorResource
import androidx.compose.ui.unit.dp
import org.xmlpull.v1.XmlPullParserException

fun ImageVector.Companion.createEmptyVector(): ImageVector {
    val builder = ImageVector.Builder(
        defaultWidth = 108F.dp,
        defaultHeight = 108F.dp,
        viewportWidth = 108F,
        viewportHeight = 108F
    )

    builder.addPath(EmptyPath, stroke = SolidColor(Color.White), strokeLineWidth = 1F)

    return builder.build()
}

fun ImageVector.Companion.vectorResourceOrNull(resources: Resources, id: Int, theme: Resources.Theme? = null): ImageVector? {
    return try {
        return ImageVector.vectorResource(theme, resources, id)
    } catch (e: XmlPullParserException) {
        null
    }
}

fun ImageVector.Companion.vectorResourceOrNull(resources: Resources, parser: XmlResourceParser, theme: Resources.Theme? = null): ImageVector? {
    return try {
        return ImageVector.vectorResource(theme, resources, parser)
    } catch (e: XmlPullParserException) {
        null
    }
}

fun ImageVector.getBuilder(): ImageVector.Builder {
    val builder = ImageVector.Builder(
        name,
        defaultWidth,
        defaultHeight,
        viewportWidth,
        viewportHeight,
        tintColor,
        tintBlendMode,
        autoMirror
    )

    for (child in root) {
        if (child is VectorGroup) {
            fillGroup(builder, child)
        }

        if (child is VectorPath) {
            fillPath(builder, child)
        }
    }

    return builder
}

private fun fillGroup(builder: ImageVector.Builder, vectorGroup: VectorGroup) {
    builder.addGroup(
        vectorGroup.name,
        vectorGroup.rotation,
        vectorGroup.pivotX,
        vectorGroup.pivotY,
        vectorGroup.scaleX,
        vectorGroup.scaleY,
        vectorGroup.translationX,
        vectorGroup.translationY,
        vectorGroup.clipPathData
    )

    for (child in vectorGroup) {
        if (child is VectorGroup) {
            fillGroup(builder, child)
        }

        if (child is VectorPath) {
            fillPath(builder, child)
        }
    }

    builder.clearGroup()
}

private fun fillPath(builder: ImageVector.Builder, vectorPath: VectorPath) {
    builder.addPath(
        vectorPath.pathData,
        vectorPath.pathFillType,
        vectorPath.name,
        vectorPath.fill,
        vectorPath.fillAlpha,
        vectorPath.stroke,
        vectorPath.strokeAlpha,
        vectorPath.strokeLineWidth,
        vectorPath.strokeLineCap,
        vectorPath.strokeLineJoin,
        vectorPath.strokeLineMiter,
        vectorPath.trimPathStart,
        vectorPath.trimPathEnd,
        vectorPath.trimPathOffset
    )
}