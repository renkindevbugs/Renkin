@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getSourceLabels
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import kotlin.math.roundToInt

private enum class GlobalStyleEffect { SHAPE, SCALE, OUTLINE, COLORIZE }

/** Native indeterminate progress, inset so its rounded caps remain visible on the dock edge. */
@Composable
internal fun GlobalPreviewProgressIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent,
        strokeCap = StrokeCap.Round
    )
}

@Composable
internal fun PreviewModeBar(
    showBefore: Boolean,
    onShowBeforeChange: (Boolean) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SegmentedRow(Modifier.weight(1f)) {
                SegmentCell(
                    label = stringResource(R.string.globalBefore),
                    selected = showBefore,
                    modifier = Modifier.weight(1f),
                    onClick = { onShowBeforeChange(true) }
                )
                SegmentCell(
                    label = stringResource(R.string.globalAfter),
                    selected = !showBefore,
                    modifier = Modifier.weight(1f),
                    onClick = { onShowBeforeChange(false) }
                )
            }
            Text(
                text = stringResource(R.string.globalLivePreview),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun TargetSummaryCard(
    state: GlobalModifierState,
    categories: GlobalIconCategories,
    targetCount: Int,
    activeEffectCount: Int,
    onClick: () -> Unit
) {
    val generatedLabel = stringResource(
        R.string.globalTargetGeneratedShort,
        categories.generated.size
    )
    val existingLabel = stringResource(
        R.string.globalTargetExistingShort,
        categories.existing.size
    )
    val customLabel = stringResource(R.string.globalTargetCustomShort, categories.custom.size)
    val emptyLabel = stringResource(R.string.globalTargetEmptyShort, categories.iconless.size)
    val noTargetsLabel = stringResource(R.string.globalNoTargets)
    val selected = buildList {
        if (state.applyGenerated) add(generatedLabel)
        if (state.applyExisting) add(existingLabel)
        if (state.applyCustom) add(customLabel)
        if (state.includeEmpty) add(emptyLabel)
    }
    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(
                        R.plurals.globalTargetCount,
                        targetCount,
                        targetCount
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (selected.isEmpty()) noTargetsLabel else selected.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.globalActiveEffects,
                        activeEffectCount,
                        activeEffectCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.globalTargetsTitle),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
internal fun GenerationSourcesCard(
    state: GlobalModifierState,
    iconPacks: List<IconPack>,
    onClick: () -> Unit
) {
    val primary = sourceDisplayName(state.primarySource, state.primaryIconPack, iconPacks)
    val secondary = sourceDisplayName(
        state.secondarySource, state.secondaryIconPack, iconPacks
    )
    val summary = if (needSecondarySource(state.primarySource)) {
        "$primary  →  $secondary"
    } else primary

    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.globalSourcesTitle),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        if (state.includeEmpty) R.string.globalSourcesUsed
                        else R.string.globalSourcesUnused
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.globalSourcesTitle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Shared contents of the wide side panel and the compact bottom dock. */
@Composable
internal fun GlobalStyleControlPanel(
    state: GlobalModifierState,
    categories: GlobalIconCategories,
    targetCount: Int,
    activeEffectCount: Int,
    iconPacks: List<IconPack>,
    resetEnabled: Boolean,
    onTargetsClick: () -> Unit,
    onSourcesClick: () -> Unit,
    onReset: () -> Unit
) {
    TargetSummaryCard(
        state = state,
        categories = categories,
        targetCount = targetCount,
        activeEffectCount = activeEffectCount,
        onClick = onTargetsClick
    )
    GenerationSourcesCard(state, iconPacks, onSourcesClick)
    GlobalStyleEffects(state)
    ResetGlobalStyleButton(resetEnabled, onReset)
}

@Composable
private fun sourceDisplayName(
    source: Source,
    packageName: String,
    iconPacks: List<IconPack>
): String {
    if (source != Source.ICON_PACK) return getSourceLabels()[source].orEmpty()
    val none = stringResource(R.string.none)
    return iconPacks.firstOrNull { it.packageName == packageName }?.applicationName
        ?: packageName.ifBlank { none }
}

@Composable
internal fun GenerationSourcesSheet(
    state: GlobalModifierState,
    iconPacks: List<IconPack>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.globalSourcesTitle),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            Text(
                text = stringResource(R.string.globalSourcesDescription),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            )
            SourceDropdown(R.string.primarySource, state.primarySource) { source ->
                state.primarySource = source
                state.normalizeFallbackSource()
            }
            if (state.primarySource == Source.ICON_PACK) {
                GlobalIconPackDropdown(
                    labelId = R.string.primaryIconPack,
                    iconPacks = iconPacks,
                    selectedPackage = state.primaryIconPack
                ) { packageName ->
                    state.primaryIconPack = packageName
                    state.normalizeFallbackSource()
                }

                SourceDropdown(R.string.secondarySource, state.secondarySource) { source ->
                    state.secondarySource = source
                    state.normalizeFallbackSource()
                }
                if (state.secondarySource == Source.ICON_PACK) {
                    GlobalIconPackDropdown(
                        labelId = R.string.secondaryIconPack,
                        iconPacks = iconPacks,
                        selectedPackage = state.secondaryIconPack
                    ) { packageName ->
                        state.secondaryIconPack = packageName
                        state.normalizeFallbackSource()
                    }
                }
            }

            val primaryPackEnabled = state.primarySource == Source.ICON_PACK &&
                state.primaryIconPack.isNotBlank()
            val secondaryPackEnabled = needSecondarySource(state.primarySource) &&
                state.secondarySource == Source.ICON_PACK &&
                state.secondaryIconPack.isNotBlank()
            if (primaryPackEnabled || secondaryPackEnabled) {
                FallbackSourceSelector(
                    selected = state.fallbackSource,
                    primaryEnabled = primaryPackEnabled,
                    secondaryEnabled = secondaryPackEnabled
                ) { state.fallbackSource = it }
            }

            Button(
                onClick = onDismiss,
                shape = FieldShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun GlobalIconPackDropdown(
    @androidx.annotation.StringRes labelId: Int,
    iconPacks: List<IconPack>,
    selectedPackage: String,
    onChange: (String) -> Unit
) {
    val none = stringResource(R.string.none)
    val labels = remember(iconPacks, selectedPackage, none) {
        linkedMapOf("" to none).apply {
            iconPacks.forEach { put(it.packageName, it.applicationName) }
            if (selectedPackage.isNotBlank() && selectedPackage !in this) {
                put(selectedPackage, selectedPackage)
            }
        }
    }
    EnumDropdown(labelId, selectedPackage, labels, onChange = onChange)
}

private fun GlobalModifierState.normalizeFallbackSource() {
    val primaryAvailable = primarySource == Source.ICON_PACK && primaryIconPack.isNotBlank()
    val secondaryAvailable = needSecondarySource(primarySource) &&
        secondarySource == Source.ICON_PACK && secondaryIconPack.isNotBlank()
    fallbackSource = when (fallbackSource) {
        FallbackSource.PRIMARY -> if (primaryAvailable) fallbackSource else FallbackSource.NONE
        FallbackSource.SECONDARY -> if (secondaryAvailable) fallbackSource else FallbackSource.NONE
        FallbackSource.NONE -> FallbackSource.NONE
    }
}

@Composable
internal fun GlobalStyleEffects(state: GlobalModifierState) {
    var colorizeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var shapeColorPickerOpen by rememberSaveable { mutableStateOf(false) }
    var outlineSheetOpen by rememberSaveable { mutableStateOf(false) }
    var expandedName by rememberSaveable { mutableStateOf(GlobalStyleEffect.SCALE.name) }

    val expanded = GlobalStyleEffect.entries.firstOrNull { it.name == expandedName }
    val off = stringResource(R.string.globalEffectOff)
    val shapeSummary = if (state.shape == IconShape.NONE) off else stringResource(
        R.string.globalShapeSummary,
        shapeLabel(state.shape),
        (state.shapeScale * 100).roundToInt()
    )
    val outlineSummary = if (state.outlineAdd) {
        stringResource(R.string.globalOutlineSummary, state.outlineWidth.roundToInt())
    } else off
    val colorizeSummary = if (!state.colorize) off else stringResource(
        if (state.colorizerStyle.mode == ColorizerMode.GRADIENT) {
            R.string.colorizerGradient
        } else {
            R.string.globalOneColor
        }
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlobalEffectCard(
            title = stringResource(R.string.iconShapeTitle),
            summary = shapeSummary,
            active = state.shape != IconShape.NONE,
            expanded = expanded == GlobalStyleEffect.SHAPE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.SHAPE) ""
                else GlobalStyleEffect.SHAPE.name
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconShape.entries.forEach { shape ->
                    ShapeSwatch(
                        shape = shape,
                        selected = state.shape == shape,
                        onClick = { state.shape = shape }
                    )
                }
            }
            AnimatedVisibility(visible = state.shape != IconShape.NONE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.shapeCrop,
                            onClick = { state.shapeCrop = true },
                            label = { Text(stringResource(R.string.shapeCrop)) }
                        )
                        FilterChip(
                            selected = !state.shapeCrop,
                            onClick = { state.shapeCrop = false },
                            label = { Text(stringResource(R.string.shapePlate)) }
                        )
                    }
                    LabeledSlider(
                        label = stringResource(R.string.shapeIconScale),
                        value = state.shapeScale,
                        onValueChange = { state.shapeScale = it },
                        valueRange = 0.5f..1.5f,
                        centered = true,
                        ruler = percentRuler()
                    )
                    if (!state.shapeCrop) {
                        val shapeStyle = state.shapeStyle
                        ColorStyleCard(
                            label = stringResource(R.string.shapeColor),
                            style = shapeStyle,
                            onClick = { shapeColorPickerOpen = true }
                        )
                        if (shapeColorPickerOpen) {
                            ColorStyleSheet(
                                title = stringResource(R.string.shapeColor),
                                initialStyle = shapeStyle,
                                sampleBitmap = null,
                                showSingleColorEffects = false,
                                onDismiss = { shapeColorPickerOpen = false },
                                onApply = { style ->
                                    state.shapeStyle = style
                                    shapeColorPickerOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        GlobalEffectCard(
            title = stringResource(R.string.iconScale),
            summary = "${(state.iconScale * 100).roundToInt()}%",
            active = state.iconScale != 1f,
            expanded = expanded == GlobalStyleEffect.SCALE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.SCALE) ""
                else GlobalStyleEffect.SCALE.name
            }
        ) {
            LabeledSlider(
                label = stringResource(R.string.iconScale),
                value = state.iconScale,
                onValueChange = { state.iconScale = it },
                valueRange = 0.5f..1.5f,
                centered = true,
                ruler = percentRuler()
            )
        }

        GlobalEffectCard(
            title = stringResource(R.string.outlineGlobal),
            summary = outlineSummary,
            active = state.outlineAdd,
            expanded = expanded == GlobalStyleEffect.OUTLINE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.OUTLINE) ""
                else GlobalStyleEffect.OUTLINE.name
            }
        ) {
            ControlledGlobalSwitchRow(
                label = stringResource(R.string.outlineGlobal),
                checked = state.outlineAdd
            ) { state.outlineAdd = it }
            AnimatedVisibility(visible = state.outlineAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.outlineThickness),
                        value = state.outlineWidth,
                        onValueChange = { state.outlineWidth = it },
                        valueRange = 1f..16f,
                        ruler = pixelRuler()
                    )
                    val outlineStyle = state.outlineStyle
                    ColorStyleCard(
                        label = stringResource(R.string.outlineColor),
                        style = outlineStyle,
                        onClick = { outlineSheetOpen = true }
                    )
                    if (outlineSheetOpen) {
                        ColorStyleSheet(
                            title = stringResource(R.string.outlineColor),
                            initialStyle = outlineStyle,
                            sampleBitmap = null,
                            showSingleColorEffects = false,
                            onDismiss = { outlineSheetOpen = false },
                            onApply = { style ->
                                state.outlineStyle = style
                                outlineSheetOpen = false
                            }
                        )
                    }
                }
            }
        }

        GlobalEffectCard(
            title = stringResource(R.string.globalColorize),
            summary = colorizeSummary,
            active = state.colorize,
            expanded = expanded == GlobalStyleEffect.COLORIZE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.COLORIZE) ""
                else GlobalStyleEffect.COLORIZE.name
            }
        ) {
            ControlledGlobalSwitchRow(
                label = stringResource(R.string.globalColorize),
                checked = state.colorize
            ) { state.colorize = it }
            AnimatedVisibility(visible = state.colorize) {
                val colorizerStyle = state.colorizerStyle
                ColorStyleCard(
                    label = stringResource(R.string.colorize),
                    style = colorizerStyle,
                    onClick = { colorizeSheetOpen = true }
                )
                if (colorizeSheetOpen) {
                    ColorStyleSheet(
                        title = stringResource(R.string.colorize),
                        initialStyle = colorizerStyle,
                        sampleBitmap = null,
                        onDismiss = { colorizeSheetOpen = false },
                        onApply = { style ->
                            state.colorizerStyle = style
                            colorizeSheetOpen = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalEffectCard(
    title: String,
    summary: String,
    active: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "globalEffectChevron")
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Surface(onClick = onExpand, color = Color.Transparent) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .weight(1f)
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .rotate(rotation)
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ControlledGlobalSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ResetGlobalStyleButton(enabled: Boolean, onReset: () -> Unit) {
    TextButton(
        onClick = onReset,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.globalResetChanges))
    }
}

@Composable
internal fun GlobalTargetsSheet(
    state: GlobalModifierState,
    categories: GlobalIconCategories,
    targetCount: Int,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.globalTargetsTitle),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.globalTargetCount,
                        targetCount,
                        targetCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            GlobalTargetRow(
                title = stringResource(R.string.globalToggleGenerated),
                description = stringResource(R.string.globalTargetGeneratedDescription),
                count = categories.generated.size,
                checked = state.applyGenerated,
                onCheckedChange = { state.applyGenerated = it }
            )
            GlobalTargetRow(
                title = stringResource(R.string.globalTargetExistingTitle),
                description = stringResource(R.string.globalTargetExistingDescription),
                count = categories.existing.size,
                checked = state.applyExisting,
                onCheckedChange = { state.applyExisting = it }
            )
            GlobalTargetRow(
                title = stringResource(R.string.globalToggleCustom),
                description = stringResource(R.string.globalTargetCustomDescription),
                count = categories.custom.size,
                checked = state.applyCustom,
                onCheckedChange = { state.applyCustom = it }
            )
            GlobalTargetRow(
                title = stringResource(R.string.globalToggleEmpty),
                description = stringResource(R.string.globalTargetEmptyDescription),
                count = categories.iconless.size,
                checked = state.includeEmpty,
                onCheckedChange = { state.includeEmpty = it }
            )
            Button(
                onClick = onDismiss,
                shape = FieldShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun GlobalTargetRow(
    title: String,
    description: String,
    count: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = CardShape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}
