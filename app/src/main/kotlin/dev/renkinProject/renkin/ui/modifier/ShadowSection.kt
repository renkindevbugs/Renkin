@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui.modifier

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.feature.presets.applyShadowPreset
import dev.renkinProject.renkin.feature.presets.shadowPreset
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.ShadowModifier
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MIN
import dev.renkinProject.renkin.icon.creator.decodeModifierPreset
import dev.renkinProject.renkin.icon.creator.encodeModifierPreset
import dev.renkinProject.renkin.ui.AdjustmentState
import dev.renkinProject.renkin.ui.shadowModifier
import dev.renkinProject.renkin.ui.AngleDial
import dev.renkinProject.renkin.ui.ColorStyleCard
import dev.renkinProject.renkin.ui.ColorStyleSheet
import dev.renkinProject.renkin.ui.LabeledSlider
import dev.renkinProject.renkin.ui.LocalAdaptiveLayoutInfo
import dev.renkinProject.renkin.ui.OptionCard
import dev.renkinProject.renkin.ui.OptionGroup
import dev.renkinProject.renkin.ui.SegmentCell
import dev.renkinProject.renkin.ui.SegmentedRow
import dev.renkinProject.renkin.ui.WavyLoadingBar
import dev.renkinProject.renkin.ui.horizontalPaneLayout
import dev.renkinProject.renkin.ui.percentRuler
import dev.renkinProject.renkin.ui.pixelRuler
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.DialogShape
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ShadowUiPreset(
    val label: String,
    val blur: Float,
    val distance: Float,
    val opacity: Float,
    val allDirections: Boolean = false
)

private enum class ShadowControl { BLUR, DISTANCE, DIRECTION, OPACITY }

@Composable
internal fun shadowUiPresets(): List<ShadowUiPreset> = listOf(
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

internal fun ShadowModifier.matchingPreset(presets: List<ShadowUiPreset>): ShadowUiPreset? = presets.firstOrNull {
    abs(blur - it.blur) < 0.01f &&
        abs(distance - it.distance) < 0.01f &&
        abs(opacity - it.opacity) < 0.001f &&
        allDirections == it.allDirections
}

private fun ShadowModifier.withPreset(preset: ShadowUiPreset): ShadowModifier = copy(
    blur = preset.blur,
    distance = preset.distance,
    opacity = preset.opacity,
    allDirections = preset.allDirections
)

private fun AdjustmentState.setShadow(shadow: ShadowModifier) {
    shadowBlur = shadow.blur
    shadowDistance = shadow.distance
    shadowAngle = shadow.angle
    shadowAllDirections = shadow.allDirections
    shadowStyle = shadow.style
    shadowOpacity = shadow.opacity
    shadowEnabled = shadow.enabled
}

@Composable
internal fun ShadowSection(
    adjustments: AdjustmentState,
    preview: Bitmap?,
    previewGenerating: Boolean,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?,
    // The host rasterises [preview] only while the editor that shows it is open.
    onEditorOpenChange: (Boolean) -> Unit = {}
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editorOpen) { onEditorOpenChange(editorOpen) }
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

    val presets = shadowUiPresets()

    Text(
        text = stringResource(R.string.shadowTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (hasPersistentPreview) {
        if (adjustments.shadowEnabled) {
            OptionGroup {
                ShadowEditorControls(
                    shadow = adjustments.shadowModifier(),
                    onShadowChange = adjustments::setShadow,
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
            shadow = adjustments.shadowModifier(),
            onShadowChange = adjustments::setShadow,
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
internal fun ShadowEditorSheet(
    shadow: ShadowModifier,
    onShadowChange: (ShadowModifier) -> Unit,
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
                    // Global Style has no single icon to show; its live grid stays the preview.
                    if (preview != null || previewGenerating) Surface(
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
                        shadow = shadow,
                        onShadowChange = onShadowChange,
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
internal fun ShadowPresetChips(
    shadow: ShadowModifier,
    presets: List<ShadowUiPreset>,
    onShadowChange: (ShadowModifier) -> Unit
) {
    val selectedPreset = shadow.matchingPreset(presets)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = selectedPreset == preset,
                onClick = { onShadowChange(shadow.withPreset(preset)) },
                label = { Text(preset.label) }
            )
        }
    }
}

@Composable
internal fun ShadowEditorControls(
    shadow: ShadowModifier,
    onShadowChange: (ShadowModifier) -> Unit,
    presets: List<ShadowUiPreset>,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?,
    // The host rasterises [preview] only while the editor that shows it is open.
    onEditorOpenChange: (Boolean) -> Unit = {},
    onColorClick: (() -> Unit)? = null
) {
    var selectedControlOrdinal by rememberSaveable { mutableIntStateOf(ShadowControl.BLUR.ordinal) }
    var colorSheetOpen by rememberSaveable { mutableStateOf(false) }
    val selectedControl = ShadowControl.entries.getOrElse(selectedControlOrdinal) { ShadowControl.BLUR }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.shadowPresets),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ShadowPresetChips(shadow, presets, onShadowChange)
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
                value = shadow.blur,
                onValueChange = { onShadowChange(shadow.copy(blur = it)) },
                valueRange = SHADOW_BLUR_MIN..SHADOW_BLUR_MAX,
                ruler = pixelRuler()
            )
            ShadowControl.DISTANCE -> LabeledSlider(
                label = stringResource(R.string.shadowDistance),
                value = shadow.distance,
                onValueChange = { onShadowChange(shadow.copy(distance = it)) },
                valueRange = SHADOW_DISTANCE_MIN..SHADOW_DISTANCE_MAX,
                ruler = pixelRuler()
            )
            ShadowControl.DIRECTION -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SegmentedRow {
                    SegmentCell(
                        label = stringResource(R.string.shadowDirectional),
                        selected = !shadow.allDirections,
                        modifier = Modifier.weight(1f),
                        onClick = { onShadowChange(shadow.copy(allDirections = false)) }
                    )
                    SegmentCell(
                        label = stringResource(R.string.shadowAllDirections),
                        selected = shadow.allDirections,
                        modifier = Modifier.weight(1f),
                        onClick = { onShadowChange(shadow.copy(allDirections = true)) }
                    )
                }
                if (!shadow.allDirections) {
                    AngleDial(
                        angle = shadow.angle,
                        contentDescription = stringResource(R.string.shadowDirection),
                        onAngleChange = { onShadowChange(shadow.copy(angle = it)) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
            ShadowControl.OPACITY -> LabeledSlider(
                label = stringResource(R.string.shadowOpacity),
                value = shadow.opacity,
                onValueChange = { onShadowChange(shadow.copy(opacity = it)) },
                valueRange = SHADOW_OPACITY_MIN..SHADOW_OPACITY_MAX,
                ruler = percentRuler()
            )
        }
        ColorStyleCard(
            label = stringResource(R.string.shadowColor),
            style = shadow.style,
            onClick = { if (onColorClick != null) onColorClick() else colorSheetOpen = true }
        )
    }

    if (colorSheetOpen) {
        ColorStyleSheet(
            title = stringResource(R.string.shadowColor),
            initialStyle = shadow.style,
            sampleBitmap = null,
            showSingleColorEffects = false,
            renderPreview = renderPreview,
            onDismiss = { colorSheetOpen = false },
            onApply = {
                onShadowChange(shadow.copy(style = it))
                colorSheetOpen = false
            }
        )
    }
}
