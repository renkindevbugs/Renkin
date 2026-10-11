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
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MIN
import dev.renkinProject.renkin.icon.creator.decodeModifierPreset
import dev.renkinProject.renkin.icon.creator.encodeModifierPreset
import dev.renkinProject.renkin.ui.AdjustmentState
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

private data class ShadowUiPreset(
    val label: String,
    val blur: Float,
    val distance: Float,
    val opacity: Float,
    val allDirections: Boolean = false
)

private enum class ShadowControl { BLUR, DISTANCE, DIRECTION, OPACITY }

@Composable
internal fun ShadowSection(
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
