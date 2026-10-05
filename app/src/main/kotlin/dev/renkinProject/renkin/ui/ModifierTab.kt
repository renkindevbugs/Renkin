@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.renkinProject.renkin.ui.theme.FieldShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.DialogShape
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getImageEditLabels
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.IconShapes
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MIN
import dev.renkinProject.renkin.icon.creator.decodeModifierPreset
import dev.renkinProject.renkin.icon.creator.encodeModifierPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Saved-state value read back as a style, tolerating the plain ARGB int older builds wrote. */
private fun Any?.toColorizerStyle(fallback: ColorizerStyle): ColorizerStyle = when (this) {
    is String -> decodeColorizerStyle(this)
    is Int -> ColorizerStyle(firstColor = this)
    else -> null
} ?: fallback

@Stable
internal class MaterialYouPackAdjustmentState(initial: MaterialYouPackEditState? = null) {
    var selectedScheme by mutableIntStateOf(initial?.selectedScheme ?: -1)
    // Full styles, not plain colours: a pack's Material You icon keeps only the first stop (its
    // layers are paths), but the generated variant can carry the whole gradient.
    var customForeground by mutableStateOf(
        initial?.customForeground ?: MaterialYouPackEditState.DEFAULT_FOREGROUND
    )
    var customBackground by mutableStateOf(
        initial?.customBackground ?: MaterialYouPackEditState.DEFAULT_BACKGROUND
    )
    var strokeScale by mutableFloatStateOf(initial?.strokeScale ?: 1f)

    fun reset() {
        selectedScheme = -1
        customForeground = MaterialYouPackEditState.DEFAULT_FOREGROUND
        customBackground = MaterialYouPackEditState.DEFAULT_BACKGROUND
        strokeScale = 1f
    }

    companion object {
        val Saver = Saver<MaterialYouPackAdjustmentState, Any>(
            save = {
                arrayListOf(
                    it.selectedScheme,
                    encodeColorizerStyle(it.customForeground),
                    encodeColorizerStyle(it.customBackground),
                    it.strokeScale
                )
            },
            restore = { saved ->
                val values = saved as? List<*>
                if (values == null) {
                    MaterialYouPackAdjustmentState()
                } else {
                    MaterialYouPackAdjustmentState().apply {
                        selectedScheme = values.getOrNull(0) as? Int ?: -1
                        // Older saved states stored plain ARGB ints; both shapes restore.
                        customForeground = values.getOrNull(1).toColorizerStyle(
                            MaterialYouPackEditState.DEFAULT_FOREGROUND
                        )
                        customBackground = values.getOrNull(2).toColorizerStyle(
                            MaterialYouPackEditState.DEFAULT_BACKGROUND
                        )
                        strokeScale = values.getOrNull(3) as? Float ?: 1f
                    }
                }
            }
        )
    }
}

internal fun lineWeightToCenteredSlider(scale: Float): Float =
    if (scale <= 1f) ((scale.coerceIn(0.5f, 1f) - 0.5f) * 2f)
    else scale.coerceIn(1f, 2f)

internal fun centeredSliderToLineWeight(value: Float): Float {
    val scale = if (value <= 1f) 0.5f + value.coerceIn(0f, 1f) * 0.5f
    else value.coerceIn(1f, 2f)
    // The label rounds to whole percent. Make every displayed 100% the exact identity value,
    // otherwise returning the thumb visually to centre could keep a tiny adjustment active.
    return if ((scale * 100).roundToInt() == 100) 1f else scale
}

private class PresetScrollAnchor {
    var y: Float = Float.NaN
    var correctionJob: Job? = null
}

@Composable
internal fun ModifierTab(
    source: Source,
    imageEdit: ImageEdit,
    iconColor: Color,
    useVector: Boolean,
    useMaterialYou: Boolean,
    adjustments: AdjustmentState,
    // The current preview icon, shown in the position tool to visualise its margins.
    centerPreview: Bitmap?,
    // True while the preview regenerates — the eraser shows a spinner during its live update.
    previewGenerating: Boolean = false,
    // The app's original icon, offered as an eyedropper source in the colour picker.
    sampleBitmap: Bitmap? = null,
    // Live previews rendered through the host's own generation pipeline, plus the artwork the
    // segment pickers cluster. Null = a host that cannot generate (no previews, no segments).
    previews: ModifierPreviews? = null,
    materialYouPackAdjustments: MaterialYouPackAdjustmentState? = null,
    materialYouSchemes: List<Pair<Color, Color>> = emptyList(),
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMaterialYouChange: (Boolean) -> Unit,
    // Hands the current icon to an external editor; true = ImageToolbox, false = user-picked app.
    onEditExternally: (toolbox: Boolean) -> Unit
) {
    val colorizeBaseBitmap = previews?.colorizeBase
    val editLabels = getImageEditLabels(includeSegments = colorizeBaseBitmap != null)
    var colorPickerOpen by remember { mutableStateOf(false) }
    var colorizeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var eraseDialogOpen by remember { mutableStateOf(false) }
    var backgroundBrushDialogOpen by remember { mutableStateOf(false) }
    var backgroundBrushPreview by remember { mutableStateOf<Bitmap?>(null) }
    var backgroundBrushPreviewGenerating by remember { mutableStateOf(false) }
    var centerDialogOpen by remember { mutableStateOf(false) }
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
        materialYouPackAdjustments?.let {
            MaterialYouPackSection(
                adjustments = it,
                schemes = materialYouSchemes,
                previews = previews,
                sampleBitmap = sampleBitmap
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
                                            renderLayersPreview = previews?.layers
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
            renderPreview = previews?.shadow
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

@Composable
private fun MaterialYouPackSection(
    adjustments: MaterialYouPackAdjustmentState,
    schemes: List<Pair<Color, Color>>,
    previews: ModifierPreviews?,
    sampleBitmap: Bitmap?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.variantMaterialYou),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (adjustments.selectedScheme >= 0 || adjustments.strokeScale != 1f) {
            TextButton(onClick = adjustments::reset) {
                Text(stringResource(R.string.resetToDefault))
            }
        }
    }
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MaterialYouColorControls(
                schemes = schemes,
                selectedScheme = adjustments.selectedScheme,
                onSchemeChange = { adjustments.selectedScheme = it },
                customForeground = adjustments.customForeground,
                customBackground = adjustments.customBackground,
                onCustomForegroundChange = { adjustments.customForeground = it },
                onCustomBackgroundChange = { adjustments.customBackground = it },
                renderForeground = previews?.materialYouPackForeground,
                renderBackground = previews?.materialYouPackBackground,
                allowOriginal = true,
                sampleBitmap = sampleBitmap
            )
            LabeledSlider(
                label = stringResource(R.string.lineThickness),
                value = lineWeightToCenteredSlider(adjustments.strokeScale),
                onValueChange = {
                    adjustments.strokeScale = centeredSliderToLineWeight(it)
                },
                valueRange = 0f..2f,
                centered = true,
                ruler = percentRuler(
                    valueRange = 0.5f..2f,
                    toRulerValue = ::centeredSliderToLineWeight,
                    fromRulerValue = ::lineWeightToCenteredSlider
                )
            )
        }
    }
}

@Composable
private fun IconAdjustmentsSection(
    adjustments: AdjustmentState,
    onOpenPosition: () -> Unit
) {
    Text(
        text = stringResource(R.string.adjustments),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    OptionGroup {
        LabeledSlider(
            label = stringResource(R.string.iconScale),
            value = adjustments.iconScale,
            onValueChange = { adjustments.iconScale = it },
            valueRange = 0.5f..1.5f,
            centered = true,
            ruler = percentRuler()
        )
    }
    OptionCard(
        label = stringResource(R.string.position),
        onClick = onOpenPosition,
        trailing = {
            val adjusted = adjustments.iconOffsetX != 0f || adjustments.iconOffsetY != 0f
            Text(
                text = if (adjusted) {
                    stringResource(R.string.positionCustom)
                } else {
                    stringResource(R.string.positionDefault)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}

@Composable
private fun IconShapeSection(
    adjustments: AdjustmentState,
    sampleBitmap: Bitmap?,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?
) {
    var colorSheetOpen by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.iconShapeTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    OptionGroup {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconShape.entries.forEach { shape ->
                ShapeSwatch(
                    shape = shape,
                    selected = adjustments.iconShape == shape,
                    onClick = { adjustments.iconShape = shape }
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = adjustments.iconShape != IconShape.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = adjustments.shapeCrop,
                        onClick = { adjustments.shapeCrop = true },
                        label = { Text(stringResource(R.string.shapeCrop)) }
                    )
                    FilterChip(
                        selected = !adjustments.shapeCrop,
                        onClick = { adjustments.shapeCrop = false },
                        label = { Text(stringResource(R.string.shapePlate)) }
                    )
                }
                LabeledSlider(
                    label = stringResource(R.string.shapeIconScale),
                    value = adjustments.shapeScale,
                    onValueChange = { adjustments.shapeScale = it },
                    valueRange = 0.5f..1.5f,
                    centered = true,
                    ruler = percentRuler()
                )
                if (!adjustments.shapeCrop) {
                    val shapeStyle = adjustments.shapeStyle
                    ColorStyleCard(
                        label = stringResource(R.string.shapeColor),
                        style = shapeStyle,
                        onClick = { colorSheetOpen = true }
                    )
                    if (colorSheetOpen) {
                        ColorStyleSheet(
                            title = stringResource(R.string.shapeColor),
                            initialStyle = shapeStyle,
                            sampleBitmap = sampleBitmap,
                            renderPreview = renderPreview,
                            showSingleColorEffects = false,
                            onDismiss = { colorSheetOpen = false },
                            onApply = { style ->
                                adjustments.shapeStyle = style
                                colorSheetOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineSection(
    adjustments: AdjustmentState,
    sampleBitmap: Bitmap?,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?,
    onOpenEraser: () -> Unit
) {
    var colorSheetOpen by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.outlineTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    OptionGroup {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineMode.entries.forEach { mode ->
                FilterChip(
                    selected = adjustments.outlineMode == mode,
                    onClick = { adjustments.outlineMode = mode },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    OutlineMode.NONE -> R.string.outlineNone
                                    OutlineMode.ADD -> R.string.outlineAdd
                                    OutlineMode.RECOLOR -> R.string.outlineRecolor
                                }
                            )
                        )
                    }
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = adjustments.outlineMode != OutlineMode.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (adjustments.outlineMode == OutlineMode.ADD) {
                    LabeledSlider(
                        label = stringResource(R.string.outlineThickness),
                        value = adjustments.outlineWidth,
                        onValueChange = { adjustments.outlineWidth = it },
                        valueRange = 1f..16f,
                        ruler = pixelRuler()
                    )
                }
                val outlineStyle = adjustments.outlineStyle
                ColorStyleCard(
                    label = stringResource(R.string.outlineColor),
                    style = outlineStyle,
                    onClick = { colorSheetOpen = true }
                )
                if (colorSheetOpen) {
                    ColorStyleSheet(
                        title = stringResource(R.string.outlineColor),
                        initialStyle = outlineStyle,
                        sampleBitmap = sampleBitmap,
                        showSingleColorEffects = false,
                        renderPreview = renderPreview,
                        onDismiss = { colorSheetOpen = false },
                        onApply = { style ->
                            adjustments.outlineStyle = style
                            colorSheetOpen = false
                        }
                    )
                }
                OptionCard(
                    label = stringResource(R.string.eraseTitle),
                    onClick = onOpenEraser,
                    trailing = {
                        Text(
                            text = if (adjustments.eraseStrokes.isEmpty()) {
                                stringResource(R.string.positionDefault)
                            } else {
                                stringResource(R.string.eraseCount, adjustments.eraseStrokes.size)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }
    }
}

private data class ShadowUiPreset(
    val label: String,
    val blur: Float,
    val distance: Float,
    val opacity: Float,
    val allDirections: Boolean = false
)

private enum class ShadowControl { BLUR, DISTANCE, DIRECTION, OPACITY }

@Composable
private fun ShadowSection(
    adjustments: AdjustmentState,
    preview: Bitmap?,
    previewGenerating: Boolean,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    // The Cancel snapshot is kept encoded so it survives process death alongside the open editor.
    var originalShadowEncoded by rememberSaveable { mutableStateOf<String?>(null) }
    val originalShadow = remember(originalShadowEncoded) {
        originalShadowEncoded?.let(::decodeModifierPreset)?.shadow
    }
    val adaptiveLayoutInfo = LocalAdaptiveLayoutInfo.current
    val hasPersistentPreview = horizontalPaneLayout(
        availableWidth = adaptiveLayoutInfo.windowWidth,
        preferredLeadingWidth = 320.dp,
        minimumLeadingWidth = 280.dp,
        minimumTrailingWidth = 360.dp,
        separatingVerticalHinge = adaptiveLayoutInfo.separatingVerticalHinge
    ) != null

    fun openEditor() {
        originalShadowEncoded =
            encodeModifierPreset(ModifierPresetPayload(shadow = adjustments.shadowPreset()))
        adjustments.shadowEnabled = true
        editorOpen = true
    }

    val presets = listOf(
        ShadowUiPreset(stringResource(R.string.shadowPresetSubtle), 4f, 2f, 0.2f),
        ShadowUiPreset(stringResource(R.string.shadowPresetSoft), 14f, 7f, 0.32f),
        ShadowUiPreset(stringResource(R.string.shadowPresetHard), 1f, 12f, 0.65f),
        ShadowUiPreset(
            stringResource(R.string.shadowPresetGlow),
            blur = 24f,
            distance = 20f,
            opacity = 0.7f,
            allDirections = true
        )
    )

    Text(
        text = stringResource(R.string.shadowTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (hasPersistentPreview) {
        if (adjustments.shadowEnabled) {
            OptionGroup {
                ShadowEditorControls(
                    adjustments = adjustments,
                    presets = presets,
                    renderPreview = renderPreview
                )
                TextButton(
                    onClick = { adjustments.shadowEnabled = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.shadowRemove))
                }
            }
        } else {
            OptionCard(
                label = stringResource(R.string.shadowEnabled),
                onClick = { adjustments.shadowEnabled = true },
                trailing = {
                    Text(
                        text = stringResource(R.string.shadowNone),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }
    } else {
        OptionCard(
            label = stringResource(R.string.shadowEnabled),
            selected = adjustments.shadowEnabled,
            onClick = ::openEditor,
            trailing = {
                Text(
                    text = if (adjustments.shadowEnabled) {
                        stringResource(
                            R.string.shadowCardSummary,
                            adjustments.shadowBlur.roundToInt(),
                            (adjustments.shadowOpacity * 100).roundToInt()
                        )
                    } else {
                        stringResource(R.string.shadowNone)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
    }

    if (editorOpen) {
        ShadowEditorSheet(
            adjustments = adjustments,
            presets = presets,
            preview = preview,
            previewGenerating = previewGenerating,
            renderPreview = renderPreview,
            showRemove = originalShadow?.enabled == true,
            onDismiss = {
                originalShadow?.let(adjustments::applyShadowPreset)
                originalShadowEncoded = null
                editorOpen = false
            },
            onApply = {
                originalShadowEncoded = null
                editorOpen = false
            },
            onRemove = {
                adjustments.shadowEnabled = false
                originalShadowEncoded = null
                editorOpen = false
            }
        )
    }
}

@Composable
private fun ShadowEditorSheet(
    adjustments: AdjustmentState,
    presets: List<ShadowUiPreset>,
    preview: Bitmap?,
    previewGenerating: Boolean,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?,
    showRemove: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onRemove: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shadowTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = CardShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(112.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            preview?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = stringResource(R.string.shadowPreview),
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxSize()
                                )
                            }
                            if (previewGenerating) {
                                WavyLoadingBar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    ShadowEditorControls(
                        adjustments = adjustments,
                        presets = presets,
                        renderPreview = renderPreview
                    )
                }
                androidx.compose.material3.HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
                ) {
                    if (showRemove) {
                        TextButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.shadowRemove))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.colorizeCancel))
                    }
                    Button(onClick = onApply) {
                        Text(stringResource(R.string.apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShadowEditorControls(
    adjustments: AdjustmentState,
    presets: List<ShadowUiPreset>,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?
) {
    var selectedControlOrdinal by rememberSaveable { mutableIntStateOf(ShadowControl.BLUR.ordinal) }
    var colorSheetOpen by rememberSaveable { mutableStateOf(false) }
    val selectedControl = ShadowControl.entries.getOrElse(selectedControlOrdinal) { ShadowControl.BLUR }
    val selectedPreset = presets.indexOfFirst {
        abs(adjustments.shadowBlur - it.blur) < 0.01f &&
        abs(adjustments.shadowDistance - it.distance) < 0.01f &&
            abs(adjustments.shadowOpacity - it.opacity) < 0.001f &&
            adjustments.shadowAllDirections == it.allDirections
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.shadowPresets),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEachIndexed { index, preset ->
                FilterChip(
                    selected = selectedPreset == index,
                    onClick = {
                        adjustments.shadowBlur = preset.blur
                        adjustments.shadowDistance = preset.distance
                        adjustments.shadowOpacity = preset.opacity
                        adjustments.shadowAllDirections = preset.allDirections
                    },
                    label = { Text(preset.label) }
                )
            }
        }
        Text(
            text = stringResource(R.string.shadowFineTune),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShadowControl.entries.forEach { control ->
                FilterChip(
                    selected = selectedControl == control,
                    onClick = { selectedControlOrdinal = control.ordinal },
                    label = {
                        Text(
                            stringResource(
                                when (control) {
                                    ShadowControl.BLUR -> R.string.shadowBlur
                                    ShadowControl.DISTANCE -> R.string.shadowDistance
                                    ShadowControl.DIRECTION -> R.string.shadowDirection
                                    ShadowControl.OPACITY -> R.string.shadowOpacity
                                }
                            )
                        )
                    }
                )
            }
        }
        when (selectedControl) {
            ShadowControl.BLUR -> LabeledSlider(
                label = stringResource(R.string.shadowBlur),
                value = adjustments.shadowBlur,
                onValueChange = { adjustments.shadowBlur = it },
                valueRange = SHADOW_BLUR_MIN..SHADOW_BLUR_MAX,
                ruler = pixelRuler()
            )
            ShadowControl.DISTANCE -> LabeledSlider(
                label = stringResource(R.string.shadowDistance),
                value = adjustments.shadowDistance,
                onValueChange = { adjustments.shadowDistance = it },
                valueRange = SHADOW_DISTANCE_MIN..SHADOW_DISTANCE_MAX,
                ruler = pixelRuler()
            )
            ShadowControl.DIRECTION -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SegmentedRow {
                    SegmentCell(
                        label = stringResource(R.string.shadowDirectional),
                        selected = !adjustments.shadowAllDirections,
                        modifier = Modifier.weight(1f),
                        onClick = { adjustments.shadowAllDirections = false }
                    )
                    SegmentCell(
                        label = stringResource(R.string.shadowAllDirections),
                        selected = adjustments.shadowAllDirections,
                        modifier = Modifier.weight(1f),
                        onClick = { adjustments.shadowAllDirections = true }
                    )
                }
                if (!adjustments.shadowAllDirections) {
                    AngleDial(
                        angle = adjustments.shadowAngle,
                        contentDescription = stringResource(R.string.shadowDirection),
                        onAngleChange = { adjustments.shadowAngle = it },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
            ShadowControl.OPACITY -> LabeledSlider(
                label = stringResource(R.string.shadowOpacity),
                value = adjustments.shadowOpacity,
                onValueChange = { adjustments.shadowOpacity = it },
                valueRange = SHADOW_OPACITY_MIN..SHADOW_OPACITY_MAX,
                ruler = percentRuler()
            )
        }
        val shadowStyle = adjustments.shadowStyle
        ColorStyleCard(
            label = stringResource(R.string.shadowColor),
            style = shadowStyle,
            onClick = { colorSheetOpen = true }
        )
    }

    if (colorSheetOpen) {
        ColorStyleSheet(
            title = stringResource(R.string.shadowColor),
            initialStyle = adjustments.shadowStyle,
            sampleBitmap = null,
            showSingleColorEffects = false,
            renderPreview = renderPreview,
            onDismiss = { colorSheetOpen = false },
            onApply = {
                adjustments.shadowStyle = it
                colorSheetOpen = false
            }
        )
    }
}

@Composable
private fun ExternalEditorSection(onEditExternally: (Boolean) -> Unit) {
    val context = LocalContext.current
    val toolboxInstalled = remember { imageToolboxInstalled(context) }
    var menuOpen by remember { mutableStateOf(false) }
    val expandedDescription = stringResource(R.string.stateExpanded)
    val collapsedDescription = stringResource(R.string.stateCollapsed)

    Text(
        text = stringResource(R.string.externalEditorTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = {
                        if (toolboxInstalled) onEditExternally(true)
                        else openImageToolboxStore(context)
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(
                        text = if (toolboxInstalled) {
                            stringResource(R.string.openInImageToolbox)
                        } else {
                            stringResource(R.string.installImageToolbox)
                        }
                    )
                }
            },
            trailingButton = {
                Box {
                    SplitButtonDefaults.TrailingButton(
                        checked = menuOpen,
                        onCheckedChange = { menuOpen = it },
                        modifier = Modifier.semantics {
                            stateDescription = if (menuOpen) expandedDescription else collapsedDescription
                        }
                    ) {
                        val rotation by animateFloatAsState(if (menuOpen) 180f else 0f, label = "chevron")
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.editInAnotherApp),
                            modifier = Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editInAnotherApp)) },
                            onClick = {
                                menuOpen = false
                                onEditExternally(false)
                            }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun ModifierDialogHost(
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

/**
 * One selectable shape preview. The path comes from the same [IconShapes] geometry the
 * generator uses, so what's drawn here is exactly what the icon gets.
 */
@Composable
internal fun ShapeSwatch(shape: IconShape, selected: Boolean, onClick: () -> Unit) {
    val label = shapeLabel(shape)
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    RenkinTooltipBox(label) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (shape == IconShape.NONE) {
                Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = label,
                    tint = fill,
                    modifier = Modifier.size(26.dp)
                )
            } else {
                Canvas(Modifier.size(30.dp)) {
                    IconShapes.path(shape, size.minDimension)?.let {
                        drawPath(it.asComposePath(), color = fill)
                    }
                }
            }
        }
    }
}

@Composable
internal fun shapeLabel(shape: IconShape): String = stringResource(
    when (shape) {
        IconShape.NONE -> R.string.shapeNone
        IconShape.CIRCLE -> R.string.shapeCircle
        IconShape.SQUIRCLE -> R.string.shapeSquircle
        IconShape.PEBBLE -> R.string.shapePebble
        IconShape.COOKIE -> R.string.shapeCookie
        IconShape.SUNNY -> R.string.shapeSunny
    }
)

private fun imageEditIcon(edit: ImageEdit): ImageVector = when (edit) {
    ImageEdit.NONE -> Icons.Filled.Block
    ImageEdit.PATH -> Icons.Filled.Gesture
    ImageEdit.EDGE -> Icons.Filled.BorderStyle
    ImageEdit.COLORIZE -> Icons.Filled.Palette
    ImageEdit.COLORIZE_SEGMENTS -> Icons.Filled.FormatColorFill
    ImageEdit.REMOVE_BACKGROUND -> Icons.Filled.LayersClear
}
