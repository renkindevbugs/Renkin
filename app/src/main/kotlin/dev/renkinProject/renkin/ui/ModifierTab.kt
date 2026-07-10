@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getImageEditLabels
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconShapes
import kotlin.math.roundToInt

/**
 * The Modifier tab's per-icon adjustment state (edge tuning, background-removal tolerance, scale,
 * position), bundled so it travels as one object instead of eight value+callback parameter pairs.
 * Plain Compose state; [Saver] keeps it across process death.
 */
@Stable
internal class AdjustmentState {
    var edgeThreshold by mutableFloatStateOf(2.5f)
    var edgeSmoothing by mutableFloatStateOf(2f)
    var edgeContrast by mutableStateOf(false)
    // Colorize as a flat fill (SRC_IN) instead of the default multiply blend, so the picked
    // colour lands exactly — a green icon tinted blue no longer muddies to a green/blue mix.
    var colorizeFlat by mutableStateOf(false)
    var iconScale by mutableFloatStateOf(1f)
    var bgRemovalTolerance by mutableFloatStateOf(0.1f)
    // Auto-center is UI state only: switching it on computes the offsets below (the pipeline's
    // single source of truth for position); dragging a position slider switches it back off.
    var autoCenter by mutableStateOf(false)
    var iconOffsetX by mutableFloatStateOf(0f)
    var iconOffsetY by mutableFloatStateOf(0f)
    // Icon shape (applied as the last generation step): the shape, plate-vs-crop mode
    // and the plate's fill colour.
    var iconShape by mutableStateOf(IconShape.NONE)
    var shapeCrop by mutableStateOf(false)
    var shapeColor by mutableStateOf(Color.White)

    companion object {
        val Saver = listSaver<AdjustmentState, Any>(
            save = {
                listOf(it.edgeThreshold, it.edgeSmoothing, it.edgeContrast, it.iconScale,
                    it.bgRemovalTolerance, it.autoCenter, it.iconOffsetX, it.iconOffsetY,
                    it.colorizeFlat, it.iconShape.ordinal, it.shapeCrop, it.shapeColor.toArgb())
            },
            restore = { saved ->
                AdjustmentState().apply {
                    edgeThreshold = saved[0] as Float
                    edgeSmoothing = saved[1] as Float
                    edgeContrast = saved[2] as Boolean
                    iconScale = saved[3] as Float
                    bgRemovalTolerance = saved[4] as Float
                    autoCenter = saved[5] as Boolean
                    iconOffsetX = saved[6] as Float
                    iconOffsetY = saved[7] as Float
                    colorizeFlat = saved[8] as Boolean
                    iconShape = IconShape.entries.getOrElse(saved[9] as Int) { IconShape.NONE }
                    shapeCrop = saved[10] as Boolean
                    shapeColor = Color(saved[11] as Int)
                }
            }
        )
    }
}

@Composable
internal fun ModifierTab(
    source: Source,
    imageEdit: ImageEdit,
    iconColor: Color,
    useVector: Boolean,
    useMonochrome: Boolean,
    adjustments: AdjustmentState,
    // The current preview icon, shown in the position tool to visualise its margins.
    centerPreview: Bitmap?,
    // The app's original icon, offered as an eyedropper source in the colour picker.
    sampleBitmap: Bitmap? = null,
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMonochromeChange: (Boolean) -> Unit,
    // Hands the current icon to an external editor; true = ImageToolbox, false = user-picked app.
    onEditExternally: (toolbox: Boolean) -> Unit
) {
    val editLabels = getImageEditLabels()
    var colorPickerOpen by remember { mutableStateOf(false) }
    var shapeColorPickerOpen by remember { mutableStateOf(false) }
    var centerDialogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toolboxInstalled = remember { imageToolboxInstalled(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.imageEdit),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Selecting an edit expands its settings inside the same envelope surface as its card,
        // so the controls visually belong to the chosen option instead of floating below the list.
        editLabels.forEach { (edit, label) ->
            val selected = imageEdit == edit
            val envelope by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                label = "editEnvelope"
            )
            Surface(shape = CardShape, color = envelope, modifier = Modifier.fillMaxWidth()) {
                Column {
                    OptionCard(
                        label = label,
                        selected = selected,
                        onClick = { onImageEditChange(edit) },
                        trailing = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else null
                    )
                    androidx.compose.animation.AnimatedVisibility(visible = selected) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when (edit) {
                                ImageEdit.EDGE -> {
                                    OptionGroup {
                                        // Detail: inverse of the Canny threshold — right = more edges kept
                                        LabeledSlider(
                                            label = stringResource(R.string.edgeDetail),
                                            value = (1f - (adjustments.edgeThreshold - 0.5f) / 4.5f).coerceIn(0f, 1f),
                                            onValueChange = { adjustments.edgeThreshold = 0.5f + (1f - it) * 4.5f },
                                            valueRange = 0f..1f
                                        )
                                        LabeledSlider(
                                            label = stringResource(R.string.edgeSmoothing),
                                            value = adjustments.edgeSmoothing,
                                            onValueChange = { adjustments.edgeSmoothing = it },
                                            valueRange = 0.5f..4f
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
                                    IconColorCard(iconColor) { colorPickerOpen = true }
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
                                                MonochromeSwitch(useMonochrome) { onMonochromeChange(it) }
                                            }
                                        }
                                    }
                                    IconColorCard(iconColor) { colorPickerOpen = true }
                                }

                                ImageEdit.COLORIZE -> {
                                    IconColorCard(iconColor) { colorPickerOpen = true }
                                    // Flat fill vs. multiply blend: on a coloured icon the multiply
                                    // default mixes the picked colour with the original, so blue over
                                    // green reads muddy. This makes the picked colour land exactly.
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.colorizeSolid),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.colorizeSolidHint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = adjustments.colorizeFlat,
                                            onCheckedChange = { adjustments.colorizeFlat = it }
                                        )
                                    }
                                }

                                ImageEdit.REMOVE_BACKGROUND -> OptionGroup {
                                    // Remove background keeps the original pixels, so no colour control.
                                    LabeledSlider(
                                        label = stringResource(R.string.removeBackgroundTolerance),
                                        value = adjustments.bgRemovalTolerance,
                                        onValueChange = { adjustments.bgRemovalTolerance = it },
                                        valueRange = 0f..0.5f,
                                        valueLabel = "${(adjustments.bgRemovalTolerance * 100).roundToInt()}%"
                                    )
                                    Text(
                                        text = stringResource(R.string.removeBackgroundHint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                ImageEdit.NONE -> {}
                            }
                        }
                    }
                }
            }
        }

        // Per-icon adjustments, independent of the modifier chosen above
        Text(
            text = stringResource(R.string.adjustments),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        OptionGroup {
            // Range centred on 1.0: left shrinks (padding), right enlarges (zoom).
            LabeledSlider(
                label = stringResource(R.string.iconScale),
                value = adjustments.iconScale,
                onValueChange = { adjustments.iconScale = it },
                valueRange = 0.5f..1.5f,
                valueLabel = "${(adjustments.iconScale * 100).roundToInt()}%",
                centered = true
            )
        }

        // Icon shape: laid on a coloured plate or cropping the icon itself, drawn with the
        // same Material You shape presets launchers use.
        Text(
            text = stringResource(R.string.iconShapeTitle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
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
                            selected = !adjustments.shapeCrop,
                            onClick = { adjustments.shapeCrop = false },
                            label = { Text(stringResource(R.string.shapePlate)) }
                        )
                        FilterChip(
                            selected = adjustments.shapeCrop,
                            onClick = { adjustments.shapeCrop = true },
                            label = { Text(stringResource(R.string.shapeCrop)) }
                        )
                    }
                    if (!adjustments.shapeCrop) {
                        OptionCard(
                            label = stringResource(R.string.shapeColor),
                            onClick = { shapeColorPickerOpen = true },
                            trailing = {
                                Surface(
                                    shape = CircleShape,
                                    color = adjustments.shapeColor,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.size(28.dp)
                                ) {}
                            }
                        )
                    }
                }
            }
        }

        // Position: opens a visual tool (like the colour picker) showing the icon's margins.
        OptionCard(
            label = stringResource(R.string.position),
            onClick = { centerDialogOpen = true },
            trailing = {
                val adjusted = adjustments.iconOffsetX != 0f || adjustments.iconOffsetY != 0f
                Text(
                    text = if (adjusted) stringResource(R.string.positionCustom) else stringResource(R.string.positionDefault),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )

        // External editor hand-off, at the end: the in-app tools above come first. A split button:
        // the main action opens ImageToolbox (or its Play Store page when not installed), the arrow
        // reveals "Edit in another app". The edited image comes back via "share to Renkin" into the
        // Upload tab.
        Text(
            text = stringResource(R.string.externalEditorTitle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )
        var editorMenuOpen by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
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
                            text = if (toolboxInstalled) stringResource(R.string.openInImageToolbox)
                                else stringResource(R.string.installImageToolbox)
                        )
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TrailingButton(
                        checked = editorMenuOpen,
                        onCheckedChange = { editorMenuOpen = it }
                    ) {
                        val rotation by animateFloatAsState(if (editorMenuOpen) 180f else 0f, label = "chevron")
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.editInAnotherApp),
                            modifier = Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }
                }
            )
            DropdownMenu(expanded = editorMenuOpen, onDismissRequest = { editorMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editInAnotherApp)) },
                    onClick = {
                        editorMenuOpen = false
                        onEditExternally(false)
                    }
                )
            }
        }
    }

    if (colorPickerOpen) {
        ColorDialog(
            onDismiss = { colorPickerOpen = false },
            currentlySelected = iconColor,
            onColorSelected = { onColorChange(it) },
            sampleBitmap = sampleBitmap
        )
    }

    if (shapeColorPickerOpen) {
        ColorDialog(
            onDismiss = { shapeColorPickerOpen = false },
            currentlySelected = adjustments.shapeColor,
            onColorSelected = { adjustments.shapeColor = it },
            sampleBitmap = sampleBitmap
        )
    }

    if (centerDialogOpen) {
        CenterDialog(
            iconBitmap = centerPreview,
            adjustments = adjustments,
            onDismiss = { centerDialogOpen = false }
        )
    }
}

/**
 * One selectable shape preview. The path comes from the same [IconShapes] geometry the
 * generator uses, so what's drawn here is exactly what the icon gets.
 */
@Composable
private fun ShapeSwatch(shape: IconShape, selected: Boolean, onClick: () -> Unit) {
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
private fun shapeLabel(shape: IconShape): String = stringResource(
    when (shape) {
        IconShape.NONE -> R.string.shapeNone
        IconShape.CIRCLE -> R.string.shapeCircle
        IconShape.SQUIRCLE -> R.string.shapeSquircle
        IconShape.PEBBLE -> R.string.shapePebble
        IconShape.COOKIE -> R.string.shapeCookie
        IconShape.SUNNY -> R.string.shapeSunny
    }
)

/** The recolouring edits' colour row: opens the picker, shows the current colour as a swatch. */
@Composable
private fun IconColorCard(iconColor: Color, onClick: () -> Unit) {
    OptionCard(
        label = stringResource(R.string.iconColor),
        onClick = onClick,
        trailing = {
            Surface(
                shape = CircleShape,
                color = iconColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(28.dp)
            ) {}
        }
    )
}
