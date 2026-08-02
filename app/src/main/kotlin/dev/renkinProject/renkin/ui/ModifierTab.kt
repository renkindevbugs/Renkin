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
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.Done
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
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
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.IconShapes
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.decodeSegmentLayer
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encode
import dev.renkinProject.renkin.icon.creator.GradientType
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
    var colorizeMonochrome by mutableStateOf(false)
    var colorizeInverse by mutableStateOf(false)
    var colorizerMode by mutableStateOf(ColorizerMode.SINGLE_COLOR)
    var colorizerGradientType by mutableStateOf(GradientType.LINEAR)
    var colorizerGradientColors by mutableStateOf(listOf(android.graphics.Color.BLACK))
    var colorizerGradientPositions by mutableStateOf(emptyList<Float>())
    var colorizerGradientAngle by mutableFloatStateOf(0f)
    // Per-region colourize steps of the Colorize segments modifier, applied in order.
    var colorizeLayers by mutableStateOf(emptyList<SegmentLayer>())
    var iconScale by mutableFloatStateOf(1f)
    // Colours the user picked to drop; empty leaves the automatic border flood in charge.
    var bgRemovalTargets by mutableStateOf(emptyList<Int>())
    var bgRemovalTolerance by mutableFloatStateOf(0.1f)
    // Auto-center is UI state only: switching it on computes the offsets below (the pipeline's
    // single source of truth for position); dragging a position slider switches it back off.
    var autoCenter by mutableStateOf(false)
    var iconOffsetX by mutableFloatStateOf(0f)
    var iconOffsetY by mutableFloatStateOf(0f)
    // Icon shape (applied as the last generation step): the shape, crop-vs-plate mode
    // (crop is the default — most icons are full-bleed), the icon's size relative to
    // the shape, and the plate's fill colour.
    var iconShape by mutableStateOf(IconShape.NONE)
    var shapeCrop by mutableStateOf(true)
    var shapeScale by mutableFloatStateOf(1f)
    var shapeColor by mutableStateOf(Color.White)
    // The plate carries its own colour style, so it can be a gradient like Colorize.
    var shapeColorizerMode by mutableStateOf(ColorizerMode.SINGLE_COLOR)
    var shapeGradientType by mutableStateOf(GradientType.LINEAR)
    var shapeGradientColors by mutableStateOf(listOf(android.graphics.Color.BLACK))
    var shapeGradientPositions by mutableStateOf(emptyList<Float>())
    var shapeGradientAngle by mutableFloatStateOf(0f)
    // Outline: add a contour around the silhouette, or recolor the icon's existing one.
    var outlineMode by mutableStateOf(OutlineMode.NONE)
    var outlineWidth by mutableFloatStateOf(6f)
    var outlineColor by mutableStateOf(Color.Black)
    // The outline carries its own colour style, so it can be a gradient like Colorize.
    var outlineColorizerMode by mutableStateOf(ColorizerMode.SINGLE_COLOR)
    var outlineGradientType by mutableStateOf(GradientType.LINEAR)
    var outlineGradientColors by mutableStateOf(listOf(android.graphics.Color.BLACK))
    var outlineGradientPositions by mutableStateOf(emptyList<Float>())
    var outlineGradientAngle by mutableFloatStateOf(0f)
    // Eraser strokes masking where the outline must not apply. Deliberately NOT in [Saver]:
    // they're transient per-app geometry, and holding them out keeps the saver list flat.
    var eraseStrokes by mutableStateOf<List<EraseStroke>>(emptyList())

    companion object {
        // Keep the keyed representation, but accept the positional list emitted by older builds.
        // mapSaver itself cannot do that because it casts every even list item to String before
        // calling restore, which would crash on the legacy list's first Float value.
        val Saver = Saver<AdjustmentState, Any>(
            save = {
                arrayListOf(
                    "edgeThreshold", it.edgeThreshold,
                    "edgeSmoothing", it.edgeSmoothing,
                    "edgeContrast", it.edgeContrast,
                    "iconScale", it.iconScale,
                    "bgRemovalTolerance", it.bgRemovalTolerance,
                    "bgRemovalTargets", it.bgRemovalTargets,
                    "autoCenter", it.autoCenter,
                    "iconOffsetX", it.iconOffsetX,
                    "iconOffsetY", it.iconOffsetY,
                    "colorizeFlat", it.colorizeFlat,
                    "colorizeMonochrome", it.colorizeMonochrome,
                    "colorizeInverse", it.colorizeInverse,
                    "colorizerMode", it.colorizerMode.ordinal,
                    "colorizerGradientType", it.colorizerGradientType.ordinal,
                    "colorizerGradientColors", it.colorizerGradientColors,
                    "colorizerGradientPositions", it.colorizerGradientPositions,
                    "colorizerGradientAngle", it.colorizerGradientAngle,
                    "colorizeLayers", it.colorizeLayers.map(SegmentLayer::encode),
                    "iconShape", it.iconShape.ordinal,
                    "shapeCrop", it.shapeCrop,
                    "shapeColor", it.shapeColor.toArgb(),
                    "shapeColorizerMode", it.shapeColorizerMode.ordinal,
                    "shapeGradientType", it.shapeGradientType.ordinal,
                    "shapeGradientColors", it.shapeGradientColors,
                    "shapeGradientPositions", it.shapeGradientPositions,
                    "shapeGradientAngle", it.shapeGradientAngle,
                    "shapeScale", it.shapeScale,
                    "outlineMode", it.outlineMode.ordinal,
                    "outlineWidth", it.outlineWidth,
                    "outlineColor", it.outlineColor.toArgb(),
                    "outlineColorizerMode", it.outlineColorizerMode.ordinal,
                    "outlineGradientType", it.outlineGradientType.ordinal,
                    "outlineGradientColors", it.outlineGradientColors,
                    "outlineGradientPositions", it.outlineGradientPositions,
                    "outlineGradientAngle", it.outlineGradientAngle
                )
            },
            restore = ::restoreAdjustmentState
        )

        private fun restoreAdjustmentState(saved: Any): AdjustmentState? {
            val values = saved as? List<*> ?: return null
            return if (values.firstOrNull() is String) {
                val keyed = buildMap<String, Any?> {
                    var index = 0
                    while (index + 1 < values.size) {
                        val key = values[index] as? String ?: break
                        put(key, values[index + 1])
                        index += 2
                    }
                }
                restoreKeyed(keyed)
            } else {
                restoreLegacy(values)
            }
        }

        private fun restoreKeyed(saved: Map<String, Any?>) = AdjustmentState().apply {
            edgeThreshold = saved["edgeThreshold"] as? Float ?: edgeThreshold
            edgeSmoothing = saved["edgeSmoothing"] as? Float ?: edgeSmoothing
            edgeContrast = saved["edgeContrast"] as? Boolean ?: edgeContrast
            iconScale = saved["iconScale"] as? Float ?: iconScale
            bgRemovalTolerance = saved["bgRemovalTolerance"] as? Float ?: bgRemovalTolerance
            (saved["bgRemovalTargets"] as? List<*>)?.filterIsInstance<Int>()?.let {
                bgRemovalTargets = it
            }
            autoCenter = saved["autoCenter"] as? Boolean ?: autoCenter
            iconOffsetX = saved["iconOffsetX"] as? Float ?: iconOffsetX
            iconOffsetY = saved["iconOffsetY"] as? Float ?: iconOffsetY
            colorizeFlat = saved["colorizeFlat"] as? Boolean ?: colorizeFlat
            colorizeMonochrome = saved["colorizeMonochrome"] as? Boolean ?: colorizeMonochrome
            colorizeInverse = saved["colorizeInverse"] as? Boolean ?: colorizeInverse
            colorizerMode = ColorizerMode.entries.getOrElse(
                saved["colorizerMode"] as? Int ?: ColorizerMode.SINGLE_COLOR.ordinal
            ) { ColorizerMode.SINGLE_COLOR }
            colorizerGradientType = GradientType.entries.getOrElse(
                saved["colorizerGradientType"] as? Int ?: GradientType.LINEAR.ordinal
            ) { GradientType.LINEAR }
            // Older saved states carried a single second colour; both shapes restore.
            (saved["colorizerGradientColors"] as? List<*>)
                ?.filterIsInstance<Int>()
                ?.takeIf { it.isNotEmpty() }
                ?.let { colorizerGradientColors = it }
                ?: (saved["colorizerGradientColor"] as? Int)?.let {
                    colorizerGradientColors = listOf(it)
                }
            (saved["colorizerGradientPositions"] as? List<*>)
                ?.filterIsInstance<Float>()
                ?.let { colorizerGradientPositions = it }
            colorizerGradientAngle =
                saved["colorizerGradientAngle"] as? Float ?: colorizerGradientAngle
            (saved["colorizeLayers"] as? List<*>)?.let { encoded ->
                colorizeLayers = encoded.filterIsInstance<String>()
                    .mapNotNull(::decodeSegmentLayer)
            }
            iconShape = IconShape.entries.getOrElse(saved["iconShape"] as? Int ?: 0) { IconShape.NONE }
            shapeCrop = saved["shapeCrop"] as? Boolean ?: shapeCrop
            (saved["shapeColor"] as? Int)?.let { shapeColor = Color(it) }
            shapeColorizerMode = ColorizerMode.entries.getOrElse(
                saved["shapeColorizerMode"] as? Int ?: ColorizerMode.SINGLE_COLOR.ordinal
            ) { ColorizerMode.SINGLE_COLOR }
            shapeGradientType = GradientType.entries.getOrElse(
                saved["shapeGradientType"] as? Int ?: GradientType.LINEAR.ordinal
            ) { GradientType.LINEAR }
            (saved["shapeGradientColors"] as? List<*>)
                ?.filterIsInstance<Int>()
                ?.takeIf { it.isNotEmpty() }
                ?.let { shapeGradientColors = it }
            (saved["shapeGradientPositions"] as? List<*>)
                ?.filterIsInstance<Float>()
                ?.let { shapeGradientPositions = it }
            shapeGradientAngle = saved["shapeGradientAngle"] as? Float ?: shapeGradientAngle
            shapeScale = saved["shapeScale"] as? Float ?: shapeScale
            outlineMode = OutlineMode.entries.getOrElse(saved["outlineMode"] as? Int ?: 0) { OutlineMode.NONE }
            outlineWidth = saved["outlineWidth"] as? Float ?: outlineWidth
            (saved["outlineColor"] as? Int)?.let { outlineColor = Color(it) }
            outlineColorizerMode = ColorizerMode.entries.getOrElse(
                saved["outlineColorizerMode"] as? Int ?: ColorizerMode.SINGLE_COLOR.ordinal
            ) { ColorizerMode.SINGLE_COLOR }
            outlineGradientType = GradientType.entries.getOrElse(
                saved["outlineGradientType"] as? Int ?: GradientType.LINEAR.ordinal
            ) { GradientType.LINEAR }
            (saved["outlineGradientColors"] as? List<*>)
                ?.filterIsInstance<Int>()
                ?.takeIf { it.isNotEmpty() }
                ?.let { outlineGradientColors = it }
            (saved["outlineGradientPositions"] as? List<*>)
                ?.filterIsInstance<Float>()
                ?.let { outlineGradientPositions = it }
            outlineGradientAngle =
                saved["outlineGradientAngle"] as? Float ?: outlineGradientAngle
        }

        private fun restoreLegacy(saved: List<*>) = AdjustmentState().apply {
            edgeThreshold = saved.getOrNull(0) as? Float ?: edgeThreshold
            edgeSmoothing = saved.getOrNull(1) as? Float ?: edgeSmoothing
            edgeContrast = saved.getOrNull(2) as? Boolean ?: edgeContrast
            iconScale = saved.getOrNull(3) as? Float ?: iconScale
            bgRemovalTolerance = saved.getOrNull(4) as? Float ?: bgRemovalTolerance
            autoCenter = saved.getOrNull(5) as? Boolean ?: autoCenter
            iconOffsetX = saved.getOrNull(6) as? Float ?: iconOffsetX
            iconOffsetY = saved.getOrNull(7) as? Float ?: iconOffsetY
            colorizeFlat = saved.getOrNull(8) as? Boolean ?: colorizeFlat
            iconShape = IconShape.entries.getOrElse(saved.getOrNull(9) as? Int ?: 0) { IconShape.NONE }
            shapeCrop = saved.getOrNull(10) as? Boolean ?: shapeCrop
            (saved.getOrNull(11) as? Int)?.let { shapeColor = Color(it) }
            shapeScale = saved.getOrNull(12) as? Float ?: shapeScale
            outlineMode = OutlineMode.entries.getOrElse(saved.getOrNull(13) as? Int ?: 0) { OutlineMode.NONE }
            outlineWidth = saved.getOrNull(14) as? Float ?: outlineWidth
            (saved.getOrNull(15) as? Int)?.let { outlineColor = Color(it) }
            colorizeMonochrome = saved.getOrNull(16) as? Boolean ?: colorizeMonochrome
            colorizeInverse = saved.getOrNull(17) as? Boolean ?: colorizeInverse
        }
    }
}

/** Saved-state value read back as a style, tolerating the plain ARGB int older builds wrote. */
private fun Any?.toColorizerStyle(fallback: Int): ColorizerStyle = when (this) {
    is String -> decodeColorizerStyle(this)
    is Int -> ColorizerStyle(firstColor = this)
    else -> null
} ?: ColorizerStyle(firstColor = fallback)

@Stable
internal class MaterialYouPackAdjustmentState {
    var selectedScheme by mutableIntStateOf(-1)
    // Full styles, not plain colours: a pack's Material You icon keeps only the first stop (its
    // layers are paths), but the generated variant can carry the whole gradient.
    var customForeground by mutableStateOf(
        ColorizerStyle(firstColor = android.graphics.Color.WHITE)
    )
    var customBackground by mutableStateOf(
        ColorizerStyle(firstColor = android.graphics.Color.BLACK)
    )
    var strokeScale by mutableFloatStateOf(1f)

    fun reset() {
        selectedScheme = -1
        customForeground = ColorizerStyle(firstColor = android.graphics.Color.WHITE)
        customBackground = ColorizerStyle(firstColor = android.graphics.Color.BLACK)
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
                            android.graphics.Color.WHITE
                        )
                        customBackground = values.getOrNull(2).toColorizerStyle(
                            android.graphics.Color.BLACK
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
    var outlineSheetOpen by rememberSaveable { mutableStateOf(false) }
    var shapeColorPickerOpen by remember { mutableStateOf(false) }
    var eraseDialogOpen by remember { mutableStateOf(false) }
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
        if (materialYouPackAdjustments != null) {
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
                if (materialYouPackAdjustments.selectedScheme >= 0 ||
                    materialYouPackAdjustments.strokeScale != 1f
                ) {
                    TextButton(onClick = materialYouPackAdjustments::reset) {
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
                        schemes = materialYouSchemes,
                        selectedScheme = materialYouPackAdjustments.selectedScheme,
                        onSchemeChange = { materialYouPackAdjustments.selectedScheme = it },
                        customForeground = materialYouPackAdjustments.customForeground,
                        customBackground = materialYouPackAdjustments.customBackground,
                        onCustomForegroundChange = {
                            materialYouPackAdjustments.customForeground = it
                        },
                        onCustomBackgroundChange = {
                            materialYouPackAdjustments.customBackground = it
                        },
                        renderForeground = previews?.materialYouPackForeground,
                        renderBackground = previews?.materialYouPackBackground,
                        allowOriginal = true,
                        sampleBitmap = sampleBitmap
                    )
                    LabeledSlider(
                        label = stringResource(R.string.lineThickness),
                        value = lineWeightToCenteredSlider(
                            materialYouPackAdjustments.strokeScale
                        ),
                        onValueChange = {
                            materialYouPackAdjustments.strokeScale =
                                centeredSliderToLineWeight(it)
                        },
                        valueRange = 0f..2f,
                        valueLabel = "${(materialYouPackAdjustments.strokeScale * 100).roundToInt()}%",
                        centered = true
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.imageEdit),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Compact icon tiles instead of five stacked full-width cards: every modifier gets a
        // glyph and the selection stands out with a primary border — the same visual language
        // as the Icon shape picker below and the watch editor's tiles.
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

        // The chosen modifier's own controls live in one envelope card under the grid.
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
                                    val colorizerStyle = ColorizerStyle(
                                        mode = adjustments.colorizerMode,
                                        gradientType = adjustments.colorizerGradientType,
                                        firstColor = iconColor.toArgb(),
                                        gradientStops = adjustments.colorizerGradientColors,
                                        gradientPositions =
                                            adjustments.colorizerGradientPositions,
                                        gradientAngle = adjustments.colorizerGradientAngle,
                                        flat = adjustments.colorizeFlat,
                                        monochrome = adjustments.colorizeMonochrome,
                                        inverse = adjustments.colorizeInverse
                                    )
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
                                                adjustments.colorizerMode = style.mode
                                                adjustments.colorizerGradientType =
                                                    style.gradientType
                                                onColorChange(Color(style.firstColor))
                                                adjustments.colorizerGradientColors =
                                                    style.gradientStops
                                                adjustments.colorizerGradientPositions =
                                                    style.gradientPositions
                                                adjustments.colorizerGradientAngle =
                                                    style.gradientAngle
                                                adjustments.colorizeFlat = style.flat
                                                adjustments.colorizeMonochrome = style.monochrome
                                                adjustments.colorizeInverse = style.inverse
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
                                            valueLabel = "${(adjustments.bgRemovalTolerance * 100).roundToInt()}%"
                                        )
                                    }
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

        // Per-icon adjustments, independent of the modifier chosen above
        Text(
            text = stringResource(R.string.adjustments),
            style = MaterialTheme.typography.titleSmallEmphasized,
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
        // Position under scale as its own card — related tools, separate controls.
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

        // Icon shape: laid on a coloured plate or cropping the icon itself, drawn with the
        // same Material You shape presets launchers use.
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
                    // Crop first — most icons are full-bleed, so cropping is the common case.
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
                    // Scales the shape itself (the icon stays as-is — that's Icon scale above):
                    // smaller crops deeper into the icon, larger clips just the corners.
                    LabeledSlider(
                        label = stringResource(R.string.shapeIconScale),
                        value = adjustments.shapeScale,
                        onValueChange = { adjustments.shapeScale = it },
                        valueRange = 0.5f..1.5f,
                        valueLabel = "${(adjustments.shapeScale * 100).roundToInt()}%",
                        centered = true
                    )
                    if (!adjustments.shapeCrop) {
                        val shapeStyle = ColorizerStyle(
                            mode = adjustments.shapeColorizerMode,
                            gradientType = adjustments.shapeGradientType,
                            firstColor = adjustments.shapeColor.toArgb(),
                            gradientStops = adjustments.shapeGradientColors,
                            gradientPositions = adjustments.shapeGradientPositions,
                            gradientAngle = adjustments.shapeGradientAngle
                        )
                        ColorStyleCard(
                            label = stringResource(R.string.shapeColor),
                            style = shapeStyle,
                            onClick = { shapeColorPickerOpen = true }
                        )
                        if (shapeColorPickerOpen) {
                            ColorStyleSheet(
                                title = stringResource(R.string.shapeColor),
                                initialStyle = shapeStyle,
                                sampleBitmap = sampleBitmap,
                                // The plate is a fill behind the icon, so the artwork switches
                                // have nothing to act on here.
                                showSingleColorEffects = false,
                                onDismiss = { shapeColorPickerOpen = false },
                                onApply = { style ->
                                    adjustments.shapeColorizerMode = style.mode
                                    adjustments.shapeGradientType = style.gradientType
                                    adjustments.shapeColor = Color(style.firstColor)
                                    adjustments.shapeGradientColors = style.gradientStops
                                    adjustments.shapeGradientPositions = style.gradientPositions
                                    adjustments.shapeGradientAngle = style.gradientAngle
                                    shapeColorPickerOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Outline: a contour around the icon's silhouette (Add), or a repaint of the ring the
        // icon already carries (Recolor) — the shape crop above still applies afterwards.
        Text(
            text = stringResource(R.string.outlineTitle),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )
        OptionGroup {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = adjustments.outlineMode == OutlineMode.NONE,
                    onClick = { adjustments.outlineMode = OutlineMode.NONE },
                    label = { Text(stringResource(R.string.outlineNone)) }
                )
                FilterChip(
                    selected = adjustments.outlineMode == OutlineMode.ADD,
                    onClick = { adjustments.outlineMode = OutlineMode.ADD },
                    label = { Text(stringResource(R.string.outlineAdd)) }
                )
                FilterChip(
                    selected = adjustments.outlineMode == OutlineMode.RECOLOR,
                    onClick = { adjustments.outlineMode = OutlineMode.RECOLOR },
                    label = { Text(stringResource(R.string.outlineRecolor)) }
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = adjustments.outlineMode != OutlineMode.NONE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Recolor finds the outline's extent by colour, so thickness only applies to Add.
                    if (adjustments.outlineMode == OutlineMode.ADD) {
                        LabeledSlider(
                            label = stringResource(R.string.outlineThickness),
                            value = adjustments.outlineWidth,
                            onValueChange = { adjustments.outlineWidth = it },
                            valueRange = 1f..16f,
                            valueLabel = "${adjustments.outlineWidth.roundToInt()} px"
                        )
                    }
                    val outlineStyle = ColorizerStyle(
                        mode = adjustments.outlineColorizerMode,
                        gradientType = adjustments.outlineGradientType,
                        firstColor = adjustments.outlineColor.toArgb(),
                        gradientStops = adjustments.outlineGradientColors,
                        gradientPositions = adjustments.outlineGradientPositions,
                        gradientAngle = adjustments.outlineGradientAngle
                    )
                    ColorStyleCard(
                        label = stringResource(R.string.outlineColor),
                        style = outlineStyle,
                        onClick = { outlineSheetOpen = true }
                    )
                    if (outlineSheetOpen) {
                        ColorStyleSheet(
                            title = stringResource(R.string.outlineColor),
                            initialStyle = outlineStyle,
                            sampleBitmap = sampleBitmap,
                            // Solid fill / monochrome / inverse describe the icon's fill, not a
                            // contour, so the outline sheet omits them.
                            showSingleColorEffects = false,
                            renderPreview = previews?.outline,
                            onDismiss = { outlineSheetOpen = false },
                            onApply = { style ->
                                adjustments.outlineColorizerMode = style.mode
                                adjustments.outlineGradientType = style.gradientType
                                adjustments.outlineColor = Color(style.firstColor)
                                adjustments.outlineGradientColors = style.gradientStops
                                adjustments.outlineGradientPositions = style.gradientPositions
                                adjustments.outlineGradientAngle = style.gradientAngle
                                outlineSheetOpen = false
                            }
                        )
                    }
                    // Eraser: paint the areas the outline must skip (per app, session-only).
                    OptionCard(
                        label = stringResource(R.string.eraseTitle),
                        onClick = { eraseDialogOpen = true },
                        trailing = {
                            Text(
                                text = if (adjustments.eraseStrokes.isEmpty()) stringResource(R.string.positionDefault)
                                    else stringResource(R.string.eraseCount, adjustments.eraseStrokes.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }
        }

        // External editor hand-off, at the end: the in-app tools above come first. A split button:
        // the main action opens ImageToolbox (or its Play Store page when not installed), the arrow
        // reveals "Edit in another app". The edited image comes back via "share to Renkin" into the
        // Upload tab.
        Text(
            text = stringResource(R.string.externalEditorTitle),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )
        var editorMenuOpen by remember { mutableStateOf(false) }
        val expandedDescription = stringResource(R.string.stateExpanded)
        val collapsedDescription = stringResource(R.string.stateCollapsed)
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
                    // The menu anchors on the trailing button itself (not the whole split
                    // button), so it opens at the chevron — above it when the button sits at
                    // the bottom of the screen — instead of drifting to the far left edge.
                    Box {
                        SplitButtonDefaults.TrailingButton(
                            checked = editorMenuOpen,
                            onCheckedChange = { editorMenuOpen = it },
                            modifier = Modifier.semantics {
                                stateDescription = if (editorMenuOpen) expandedDescription
                                    else collapsedDescription
                            }
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
            )
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


    if (centerDialogOpen) {
        CenterDialog(
            iconBitmap = centerPreview,
            adjustments = adjustments,
            renderPositionBase = previews?.positionBase,
            onDismiss = { centerDialogOpen = false }
        )
    }

    if (eraseDialogOpen) {
        EraseDialog(
            iconBitmap = centerPreview,
            strokes = adjustments.eraseStrokes,
            onStrokesChange = { adjustments.eraseStrokes = it },
            generating = previewGenerating,
            onDismiss = { eraseDialogOpen = false }
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

/** The tile glyph giving each image modifier a visual identity in the selector grid. */
private fun imageEditIcon(edit: ImageEdit): ImageVector = when (edit) {
    ImageEdit.NONE -> Icons.Filled.Block
    ImageEdit.PATH -> Icons.Filled.Gesture
    ImageEdit.EDGE -> Icons.Filled.BorderStyle
    ImageEdit.COLORIZE -> Icons.Filled.Palette
    ImageEdit.COLORIZE_SEGMENTS -> Icons.Filled.FormatColorFill
    ImageEdit.REMOVE_BACKGROUND -> Icons.Filled.LayersClear
}
