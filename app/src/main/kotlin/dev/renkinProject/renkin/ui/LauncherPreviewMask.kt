package dev.renkinProject.renkin.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconShapes

/** Preview-only masks; the icon's exported artwork is never changed by this choice. */
internal enum class LauncherPreviewMask(val shape: Shape, @StringRes val label: Int) {
    SQUARE(RectangleShape, R.string.previewMaskSquare),
    ROUNDED(RoundedCornerShape(28), R.string.previewMaskRounded),
    SQUIRCLE(GenericShape { size, _ ->
        IconShapes.path(IconShape.SQUIRCLE, size.width)?.let { addPath(it.asComposePath()) }
    }, R.string.shapeSquircle),
    CIRCLE(CircleShape, R.string.shapeCircle)
}
