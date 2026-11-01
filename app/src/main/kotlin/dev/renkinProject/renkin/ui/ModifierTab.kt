@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.feature.presets.ModifierPresetsSection
import dev.renkinProject.renkin.feature.presets.RecentModifierPresetsSection
import dev.renkinProject.renkin.feature.presets.colorizerStyleWith
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.AppBackgroundColor
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getImageEditLabels
import dev.renkinProject.renkin.ui.modifier.ExternalEditorSection
import dev.renkinProject.renkin.ui.modifier.IconAdjustmentsSection
import dev.renkinProject.renkin.ui.modifier.IconShapeSection
import dev.renkinProject.renkin.ui.modifier.MaterialYouPackSection
import dev.renkinProject.renkin.ui.modifier.ModifierDialogHost
import dev.renkinProject.renkin.ui.modifier.OutlineSection
import dev.renkinProject.renkin.ui.modifier.ShadowSection
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class PresetScrollAnchor {
    var y: Float = Float.NaN
    var correctionJob: Job? = null
}

@Composable
internal fun ModifierTab(
    leadingContent: @Composable () -> Unit = {},
    allowImageEdits: Boolean = true,
    source: Source,
    imageEdit: ImageEdit,
    iconColor: Color,
    useVector: Boolean,
    useMaterialYou: Boolean,
    adjustments: AdjustmentState,
    // The current preview icon, shown in the position tool to visualise its margins.
    centerPreviewIcon: IconPackDrawable?,
    // True while the preview regenerates — the eraser shows a spinner during its live update.
    previewGenerating: Boolean = false,
    // The app's original icon, offered as an eyedropper source in the colour picker.
    sampleBitmap: Bitmap? = null,
    // Live previews rendered through the host's own generation pipeline, plus the artwork the
    // segment pickers cluster. Null = a host that cannot generate (no previews, no segments).
    previews: ModifierPreviews? = null,
    materialYouPackAdjustments: MaterialYouPackAdjustmentState? = null,
    materialYouSchemes: List<Pair<Color, Color>> = emptyList(),
    materialYouPackLineThicknessEditable: Boolean = true,
    // The app's own brand colour for "Match app color"; null hides that action.
    appBackground: AppBackgroundColor? = null,
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMaterialYouChange: (Boolean) -> Unit,
    // Hands the current icon to an external editor; true = ImageToolbox, false = user-picked app.
    onEditExternally: (toolbox: Boolean) -> Unit
) {
    val colorizeBaseBitmap = previews?.colorizeBase
    val editLabels = getImageEditLabels(includeSegments = colorizeBaseBitmap != null)
        .filterKeys { allowImageEdits || it == ImageEdit.NONE }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var colorizeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var eraseDialogOpen by remember { mutableStateOf(false) }
    var backgroundBrushDialogOpen by remember { mutableStateOf(false) }
    var backgroundBrushPreview by remember { mutableStateOf<Bitmap?>(null) }
    var backgroundBrushPreviewGenerating by remember { mutableStateOf(false) }
    var centerDialogOpen by remember { mutableStateOf(false) }
    var shadowEditorOpen by remember { mutableStateOf(false) }
    // Rasterising the edited icon is not free: only the dialogs below show it, so do it off Main
    // and only while one of them is open.
    val centerPreviewShown = centerDialogOpen || eraseDialogOpen || backgroundBrushDialogOpen || shadowEditorOpen
    val centerPreview by produceState<Bitmap?>(null, centerPreviewIcon, centerPreviewShown) {
        value = if (centerPreviewShown) withContext(Dispatchers.Default) { centerPreviewIcon?.toModifierBitmap() } else null
    }
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    val presetScrollAnchor = remember { PresetScrollAnchor() }
    // Brush coordinates belong to the image-edit stage. Rendering the final, transformed icon
    // here would make strokes miss whenever Scale, Position, Shape or Outline is active.
    LaunchedEffect(backgroundBrushDialogOpen, adjustments.backgroundBrushStrokes, previews) {
        if (!backgroundBrushDialogOpen) {
            backgroundBrushPreview = null
            backgroundBrushPreviewGenerating = false
            return@LaunchedEffect
        }
        backgroundBrushPreviewGenerating = true
        try {
            backgroundBrushPreview = previews?.backgroundBrush?.invoke()
        } finally {
            backgroundBrushPreviewGenerating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        leadingContent()
        RecentModifierPresetsSection(adjustments, previews) { applied ->
            applied.imageEdit?.let(onImageEditChange)
            applied.iconColor?.let(onColorChange)
        }
        materialYouPackAdjustments?.let {
            MaterialYouPackSection(
                adjustments = it,
                schemes = materialYouSchemes,
                previews = previews,
                sampleBitmap = sampleBitmap,
                lineThicknessEditable = materialYouPackLineThicknessEditable
            )
        }

        Text(
            text = stringResource(R.string.imageEdit),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )

        editLabels.entries.toList().chunked(3).forEach { rowEdits ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowEdits.forEach { (edit, label) ->
                    val selected = imageEdit == edit
                    Surface(
                        shape = FieldShape,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .clip(FieldShape)
                            .clickable(role = Role.Button) { onImageEditChange(edit) }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = imageEditIcon(edit),
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                repeat(3 - rowEdits.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        androidx.compose.animation.AnimatedVisibility(visible = imageEdit != ImageEdit.NONE) {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when (imageEdit) {
                                ImageEdit.EDGE -> {
                                    OptionGroup {
                                        // Detail: inverse of the Canny threshold — right = more edges kept
                                        LabeledSlider(
                                            label = stringResource(R.string.edgeDetail),
                                            value = (1f - (adjustments.edgeThreshold - 0.5f) / 4.5f).coerceIn(0f, 1f),
                                            onValueChange = { adjustments.edgeThreshold = 0.5f + (1f - it) * 4.5f },
                                            valueRange = 0f..1f,
                                            ruler = percentRuler()
                                        )
                                        LabeledSlider(
                                            label = stringResource(R.string.edgeSmoothing),
                                            value = adjustments.edgeSmoothing,
                                            onValueChange = { adjustments.edgeSmoothing = it },
                                            valueRange = 0.5f..4f,
                                            // A blur radius, not a percentage: one decimal is the
                                            // finest step that still changes the traced edges.
                                            ruler = decimalRuler()
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.edgeContrast),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = adjustments.edgeContrast,
                                                onCheckedChange = { adjustments.edgeContrast = it }
                                            )
                                        }
                                    }
                                    ColorRow(stringResource(R.string.iconColor), iconColor) { colorPickerOpen = true }
                                }

                                ImageEdit.PATH -> {
                                    if (isPathTracingEnabled(source, imageEdit)) {
                                        Surface(
                                            shape = CardShape,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                VectorSwitch(useVector) { onVectorChange(it) }
                                                MaterialYouSwitch(useMaterialYou) { onMaterialYouChange(it) }
                                            }
                                        }
                                    }
                                    ColorRow(stringResource(R.string.iconColor), iconColor) { colorPickerOpen = true }
                                }

                                ImageEdit.COLORIZE -> {
                                    val colorizerStyle = adjustments.colorizerStyleWith(iconColor)
                                    ColorStyleCard(
                                        label = stringResource(R.string.colorize),
                                        style = colorizerStyle,
                                        onClick = { colorizeSheetOpen = true }
                                    )
                                    if (colorizeSheetOpen) {
                                        ColorStyleSheet(
                                            title = stringResource(R.string.colorize),
                                            initialStyle = colorizerStyle,
                                            sampleBitmap = sampleBitmap,
                                            renderPreview = previews?.colorize,
                                            onDismiss = { colorizeSheetOpen = false },
                                            onApply = { style ->
                                                adjustments.colorizerStyle = style
                                                onColorChange(Color(style.firstColor))
                                                colorizeSheetOpen = false
                                            }
                                        )
                                    }
                                }

                                ImageEdit.COLORIZE_SEGMENTS -> {
                                    colorizeBaseBitmap?.let { base ->
                                        SegmentLayerEditor(
                                            source = base,
                                            sampleBitmap = sampleBitmap,
                                            layers = adjustments.colorizeLayers,
                                            onLayersChange = { adjustments.colorizeLayers = it },
                                            renderLayersPreview = previews?.layers,
                                            appBackground = appBackground
                                        )
                                    }
                                }

                                ImageEdit.REMOVE_BACKGROUND -> OptionGroup {
                                    // Remove background keeps the original pixels, so no colour
                                    // control — but the user can say WHICH colour is background
                                    // when the automatic guess picks the wrong one.
                                    val base = colorizeBaseBitmap
                                    if (base != null) {
                                        SegmentSelector(
                                            source = base,
                                            targets = adjustments.bgRemovalTargets,
                                            tolerance = adjustments.bgRemovalTolerance,
                                            onTargetsChange = {
                                                adjustments.bgRemovalTargets = it
                                            },
                                            onToleranceChange = {
                                                adjustments.bgRemovalTolerance = it
                                            },
                                            toleranceRange = 0f..0.5f,
                                            selectionActive =
                                                adjustments.bgRemovalTolerance > 0f,
                                            emptyHint = stringResource(
                                                R.string.removeBackgroundAutoHint
                                            )
                                        )
                                    } else {
                                        LabeledSlider(
                                            label = stringResource(R.string.removeBackgroundTolerance),
                                            value = adjustments.bgRemovalTolerance,
                                            onValueChange = { adjustments.bgRemovalTolerance = it },
                                            valueRange = 0f..0.5f,
                                            ruler = percentRuler()
                                        )
                                    }
                                    // The colour match works in whole regions; edges and leftover
                                    // fringes need a hand. Restore is on the same brush so a
                                    // stroke that took too much can be walked back in place.
                                    OptionCard(
                                        label = stringResource(R.string.backgroundBrushTitle),
                                        onClick = { backgroundBrushDialogOpen = true },
                                        trailing = {
                                            Text(
                                                text = if (adjustments.backgroundBrushStrokes.isEmpty()) {
                                                    stringResource(R.string.positionDefault)
                                                } else {
                                                    stringResource(
                                                        R.string.eraseCount,
                                                        adjustments.backgroundBrushStrokes.size
                                                    )
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    )
                                }

                                ImageEdit.NONE -> {}
                            }
                        }
            }
        }

        IconAdjustmentsSection(
            adjustments = adjustments,
            onOpenPosition = { centerDialogOpen = true }
        )
        IconShapeSection(
            adjustments = adjustments,
            sampleBitmap = sampleBitmap,
            renderPreview = previews?.shape
        )
        OutlineSection(
            adjustments = adjustments,
            sampleBitmap = sampleBitmap,
            renderPreview = previews?.outline,
            onOpenEraser = { eraseDialogOpen = true }
        )
        ShadowSection(
            adjustments = adjustments,
            preview = centerPreview,
            previewGenerating = previewGenerating,
            renderPreview = previews?.shadow,
            onEditorOpenChange = { shadowEditorOpen = it }
        )
        ExternalEditorSection(onEditExternally)

        // Presets close the tab: everything above is what a preset can capture, and loading one
        // rewrites those blocks in place. Only the tab's own Apply then touches the icon.
        ModifierPresetsSection(
            adjustments = adjustments,
            imageEdit = imageEdit,
            iconColor = iconColor,
            previews = previews,
            modifier = Modifier.onGloballyPositioned { coordinates ->
                presetScrollAnchor.y = coordinates.positionInRoot().y
            },
            onPresetLoaded = { applied ->
                val anchorY = presetScrollAnchor.y
                applied.imageEdit?.let(onImageEditChange)
                applied.iconColor?.let(onColorChange)
                if (anchorY.isFinite()) {
                    presetScrollAnchor.correctionJob?.cancel()
                    presetScrollAnchor.correctionJob = scrollScope.launch {
                        // AnimatedVisibility can change the height above this section for several
                        // frames. Correct each layout delta until the anchor settles, rather than
                        // jumping to a hard-coded scroll position.
                        var stableFrames = 0
                        repeat(30) {
                            withFrameNanos { }
                            val delta = presetScrollAnchor.y - anchorY
                            if (!delta.isFinite()) return@launch
                            if (abs(delta) < 0.5f) {
                                stableFrames++
                                if (stableFrames >= 3) return@launch
                            } else {
                                stableFrames = 0
                                val consumed = scrollState.scrollBy(delta)
                                if (abs(consumed) < 0.5f) return@launch
                            }
                        }
                    }
                }
            }
        )
    }

    ModifierDialogHost(
        colorPickerOpen = colorPickerOpen,
        centerDialogOpen = centerDialogOpen,
        eraseDialogOpen = eraseDialogOpen,
        backgroundBrushDialogOpen = backgroundBrushDialogOpen,
        iconColor = iconColor,
        sampleBitmap = sampleBitmap,
        centerPreview = centerPreview,
        adjustments = adjustments,
        previews = previews,
        previewGenerating = previewGenerating,
        backgroundBrushPreview = backgroundBrushPreview,
        backgroundBrushPreviewGenerating = backgroundBrushPreviewGenerating,
        onColorChange = onColorChange,
        onCloseColorPicker = { colorPickerOpen = false },
        onCloseCenter = { centerDialogOpen = false },
        onCloseEraser = { eraseDialogOpen = false },
        onCloseBackgroundBrush = { backgroundBrushDialogOpen = false }
    )
}

private fun imageEditIcon(edit: ImageEdit): ImageVector = when (edit) {
    ImageEdit.NONE -> Icons.Filled.Block
    ImageEdit.PATH -> Icons.Filled.Gesture
    ImageEdit.EDGE -> Icons.Filled.BorderStyle
    ImageEdit.COLORIZE -> Icons.Filled.Palette
    ImageEdit.COLORIZE_SEGMENTS -> Icons.Filled.FormatColorFill
    ImageEdit.REMOVE_BACKGROUND -> Icons.Filled.LayersClear
}
