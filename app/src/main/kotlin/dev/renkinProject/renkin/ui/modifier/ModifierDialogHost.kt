@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui.modifier

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.AdjustmentState
import dev.renkinProject.renkin.ui.CenterDialog
import dev.renkinProject.renkin.ui.ColorDialog
import dev.renkinProject.renkin.ui.EraseDialog
import dev.renkinProject.renkin.ui.ModifierPreviews

@Composable
internal fun ModifierDialogHost(
    colorPickerOpen: Boolean,
    centerDialogOpen: Boolean,
    eraseDialogOpen: Boolean,
    backgroundBrushDialogOpen: Boolean,
    iconColor: Color,
    sampleBitmap: Bitmap?,
    centerPreview: Bitmap?,
    adjustments: AdjustmentState,
    previews: ModifierPreviews?,
    previewGenerating: Boolean,
    backgroundBrushPreview: Bitmap?,
    backgroundBrushPreviewGenerating: Boolean,
    onColorChange: (Color) -> Unit,
    onCloseColorPicker: () -> Unit,
    onCloseCenter: () -> Unit,
    onCloseEraser: () -> Unit,
    onCloseBackgroundBrush: () -> Unit
) {
    if (colorPickerOpen) {
        ColorDialog(
            onDismiss = onCloseColorPicker,
            currentlySelected = iconColor,
            onColorSelected = onColorChange,
            sampleBitmap = sampleBitmap
        )
    }
    if (centerDialogOpen) {
        CenterDialog(
            iconBitmap = centerPreview,
            adjustments = adjustments,
            renderPositionBase = previews?.positionBase,
            onDismiss = onCloseCenter
        )
    }
    if (eraseDialogOpen) {
        EraseDialog(
            iconBitmap = centerPreview,
            strokes = adjustments.eraseStrokes,
            onStrokesChange = { adjustments.eraseStrokes = it },
            generating = previewGenerating,
            onDismiss = onCloseEraser
        )
    }
    if (backgroundBrushDialogOpen) {
        EraseDialog(
            iconBitmap = backgroundBrushPreview ?: previews?.colorizeBase ?: centerPreview,
            strokes = adjustments.backgroundBrushStrokes,
            onStrokesChange = { adjustments.backgroundBrushStrokes = it },
            generating = previewGenerating || backgroundBrushPreviewGenerating,
            allowRestore = true,
            title = R.string.backgroundBrushTitle,
            onDismiss = onCloseBackgroundBrush
        )
    }
}
