@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.icon.creator.MODIFIER_SCALE_RANGE
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getSourceLabels
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.ui.modifier.ShadowEditorControls
import dev.renkinProject.renkin.ui.modifier.ShadowPresetChips
import dev.renkinProject.renkin.ui.modifier.ShapeSwatch
import dev.renkinProject.renkin.ui.modifier.matchingPreset
import dev.renkinProject.renkin.ui.modifier.shapeLabel
import dev.renkinProject.renkin.ui.modifier.shadowUiPresets
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.drawable.ResourceDrawable
import kotlin.math.roundToInt

private enum class GlobalStyleEffect { SHAPE, SCALE, OUTLINE, SHADOW, COLORIZE }

@Composable
private fun GlobalShadowCard(
    state: GlobalModifierState,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCustomize: () -> Unit
) {
    val presets = shadowUiPresets()
    val shadow = state.shadow
    val summary = when {
        !shadow.enabled -> stringResource(R.string.globalEffectOff)
        else -> shadow.matchingPreset(presets)?.label ?: stringResource(
            R.string.shadowCardSummary, shadow.blur.roundToInt(), (shadow.opacity * 100).roundToInt()
        )
    }

    GlobalEffectCard(
        title = stringResource(R.string.shadowTitle),
        summary = summary,
        active = shadow.enabled,
        expanded = expanded,
        onExpand = onExpand
    ) {
        ControlledGlobalSwitchRow(
            label = stringResource(R.string.shadowEnabled),
            checked = shadow.enabled
        ) { state.updateShadow(shadow.copy(enabled = it)) }
        AnimatedVisibility(visible = shadow.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadowPresetChips(shadow, presets, state::updateShadow)
                OptionCard(
                    label = stringResource(R.string.globalShadowCustomize),
                    onClick = onCustomize,
                    trailing = {
                        Text(
                            text = stringResource(
                                R.string.shadowCardSummary,
                                shadow.blur.roundToInt(),
                                (shadow.opacity * 100).roundToInt()
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                Text(
                    text = stringResource(R.string.globalShadowFlattenHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

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
    onReset: () -> Unit,
    navigation: GlobalStylePanelState,
    onNavigate: (GlobalStylePanel) -> Unit,
    loadPackIcons: suspend () -> Map<String, ResourceDrawable>,
    historyContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: @Composable () -> Unit = {}
) {
    val panelState = rememberSaveableStateHolder()
    val page = navigation.page
    val contentScrolls = page != GlobalStylePanel.HISTORY && page != GlobalStylePanel.GRADIENTS &&
        page != GlobalStylePanel.SAVED_COLORS
    Column(modifier) {
        if (page != GlobalStylePanel.OPTIONS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { navigation.back() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(
                            R.string.globalPanelBackTo, stringResource(navigation.parent.title)
                        )
                    )
                }
                Text(
                    text = stringResource(page.title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (page == GlobalStylePanel.SHADOW) TextButton(onClick = {
                    state.updateShadow(state.shadow.copy(enabled = false))
                    navigation.back()
                }) {
                    Text(stringResource(R.string.shadowRemove), color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
        }
        val contentKey = when (page) {
            GlobalStylePanel.GRADIENTS, GlobalStylePanel.SAVED_COLORS -> "${page.name}:${navigation.colorTarget.name}"
            else -> page.name
        }
        panelState.SaveableStateProvider(contentKey) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (contentScrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(top = if (page != GlobalStylePanel.OPTIONS) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (page) {
                    GlobalStylePanel.OPTIONS -> {
                        leadingContent()
                        TargetSummaryCard(state, categories, targetCount, activeEffectCount) {
                            onNavigate(GlobalStylePanel.TARGETS)
                        }
                        GenerationSourcesCard(state, iconPacks) { onNavigate(GlobalStylePanel.SOURCES) }
                        GlobalStyleEffects(state, onNavigate)
                        ResetGlobalStyleButton(resetEnabled, onReset)
                    }
                    GlobalStylePanel.SHADOW -> {
                        ShadowEditorControls(
                            shadow = state.shadow,
                            onShadowChange = state::updateShadow,
                            presets = shadowUiPresets(),
                            renderPreview = null,
                            onColorClick = { onNavigate(GlobalStylePanel.SHADOW_COLOR) }
                        )
                        Text(
                            text = stringResource(R.string.globalShadowFlattenHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GlobalStylePanel.TARGETS -> GlobalTargetsControls(state, categories, targetCount) { navigation.back() }
                    GlobalStylePanel.SOURCES -> GenerationSourcesControls(state, iconPacks, loadPackIcons) { navigation.back() }
                    GlobalStylePanel.HISTORY -> historyContent()
                    else -> GlobalStyleColorControls(state, navigation)
                }
            }
        }
        if (page.isColorEditor) {
            HorizontalDivider()
            GlobalStyleColorActions(state, navigation)
        }
    }
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
internal fun GenerationSourcesControls(
    state: GlobalModifierState,
    iconPacks: List<IconPack>,
    loadPackIcons: suspend () -> Map<String, ResourceDrawable>,
    onDone: () -> Unit
) {
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
        IconPackDropdown(
            labelId = R.string.primaryIconPack,
            iconPacks = iconPacks,
            packageName = state.primaryIconPack,
            loadIcons = loadPackIcons
        ) { packageName ->
            state.primaryIconPack = packageName
            state.normalizeFallbackSource()
        }

        SourceDropdown(R.string.secondarySource, state.secondarySource) { source ->
            state.secondarySource = source
            state.normalizeFallbackSource()
        }
        if (state.secondarySource == Source.ICON_PACK) {
            IconPackDropdown(
                labelId = R.string.secondaryIconPack,
                iconPacks = iconPacks,
                packageName = state.secondaryIconPack,
                loadIcons = loadPackIcons
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
        onClick = onDone,
        shape = FieldShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Text(stringResource(R.string.done))
    }
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
internal fun GlobalStyleEffects(state: GlobalModifierState, onNavigate: (GlobalStylePanel) -> Unit) {
    var expandedName by rememberSaveable { mutableStateOf(GlobalStyleEffect.SCALE.name) }

    val expanded = GlobalStyleEffect.entries.firstOrNull { it.name == expandedName }
    val colorize = state.modifiers.effect.imageEdit == ImageEdit.COLORIZE
    val off = stringResource(R.string.globalEffectOff)
    val shapeSummary = if (!state.shape.isEnabled) off else stringResource(
        R.string.globalShapeSummary,
        shapeLabel(state.shape.shape),
        (state.shape.scale * 100).roundToInt()
    )
    val outlineSummary = if (state.outline.isEnabled) {
        stringResource(R.string.globalOutlineSummary, state.outline.width.roundToInt())
    } else off
    val colorizeSummary = if (!colorize) off else stringResource(
        if (state.modifiers.effect.colorizer.mode == ColorizerMode.GRADIENT) {
            R.string.colorizerGradient
        } else {
            R.string.globalOneColor
        }
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlobalEffectCard(
            title = stringResource(R.string.iconShapeTitle),
            summary = shapeSummary,
            active = state.shape.isEnabled,
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
                IconShape.entries.forEach { selectedShape ->
                    ShapeSwatch(
                        shape = selectedShape,
                        selected = state.shape.shape == selectedShape,
                        onClick = { state.updateShape { copy(shape = selectedShape) } }
                    )
                }
            }
            AnimatedVisibility(visible = state.shape.isEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.shape.crop,
                            onClick = { state.updateShape { copy(crop = true) } },
                            label = { Text(stringResource(R.string.shapeCrop)) }
                        )
                        FilterChip(
                            selected = !state.shape.crop,
                            onClick = { state.updateShape { copy(crop = false) } },
                            label = { Text(stringResource(R.string.shapePlate)) }
                        )
                    }
                    LabeledSlider(
                        label = stringResource(R.string.shapeIconScale),
                        value = state.shape.scale,
                        onValueChange = { state.updateShape { copy(scale = it) } },
                        valueRange = MODIFIER_SCALE_RANGE,
                        centered = true,
                        ruler = percentRuler()
                    )
                    if (!state.shape.crop) {
                        val shapeStyle = requireNotNull(state.shape.fill?.style)
                        ColorStyleCard(
                            label = stringResource(R.string.shapeColor),
                            style = shapeStyle,
                            onClick = { onNavigate(GlobalStylePanel.SHAPE_COLOR) }
                        )

                    }
                }
            }
        }

        GlobalEffectCard(
            title = stringResource(R.string.iconScale),
            summary = formatPercent(state.modifiers.transform.scale),
            active = state.modifiers.transform.scale != 1f,
            expanded = expanded == GlobalStyleEffect.SCALE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.SCALE) ""
                else GlobalStyleEffect.SCALE.name
            }
        ) {
            LabeledSlider(
                label = stringResource(R.string.iconScale),
                value = state.modifiers.transform.scale,
                onValueChange = { state.updateTransform { copy(scale = it) } },
                valueRange = MODIFIER_SCALE_RANGE,
                centered = true,
                ruler = percentRuler()
            )
        }

        GlobalEffectCard(
            title = stringResource(R.string.outlineGlobal),
            summary = outlineSummary,
            active = state.outline.isEnabled,
            expanded = expanded == GlobalStyleEffect.OUTLINE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.OUTLINE) ""
                else GlobalStyleEffect.OUTLINE.name
            }
        ) {
            ControlledGlobalSwitchRow(
                label = stringResource(R.string.outlineGlobal),
                checked = state.outline.isEnabled
            ) { state.updateOutline { copy(mode = if (it) OutlineMode.ADD else OutlineMode.NONE) } }
            AnimatedVisibility(visible = state.outline.isEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.outlineThickness),
                        value = state.outline.width,
                        onValueChange = { state.updateOutline { copy(width = it) } },
                        valueRange = 1f..16f,
                        ruler = pixelRuler()
                    )
                    val outlineStyle = requireNotNull(state.outline.fill.style)
                    ColorStyleCard(
                        label = stringResource(R.string.outlineColor),
                        style = outlineStyle,
                        onClick = { onNavigate(GlobalStylePanel.OUTLINE_COLOR) }
                    )

                }
            }
        }

        GlobalShadowCard(
            state = state,
            onCustomize = { onNavigate(GlobalStylePanel.SHADOW) },
            expanded = expanded == GlobalStyleEffect.SHADOW,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.SHADOW) ""
                else GlobalStyleEffect.SHADOW.name
            }
        )

        GlobalEffectCard(
            title = stringResource(R.string.globalColorize),
            summary = colorizeSummary,
            active = colorize,
            expanded = expanded == GlobalStyleEffect.COLORIZE,
            onExpand = {
                expandedName = if (expanded == GlobalStyleEffect.COLORIZE) ""
                else GlobalStyleEffect.COLORIZE.name
            }
        ) {
            ControlledGlobalSwitchRow(
                label = stringResource(R.string.globalColorize),
                checked = colorize
            ) { state.updateEffect { copy(imageEdit = if (it) ImageEdit.COLORIZE else ImageEdit.NONE) } }
            AnimatedVisibility(visible = colorize) {
                val colorizerStyle = state.modifiers.effect.colorizer
                ColorStyleCard(
                    label = stringResource(R.string.colorize),
                    style = colorizerStyle,
                    onClick = { onNavigate(GlobalStylePanel.COLORIZE) }
                )

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
internal fun GlobalTargetsControls(
    state: GlobalModifierState,
    categories: GlobalIconCategories,
    targetCount: Int,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {

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
        onClick = onDone,
        shape = FieldShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Text(stringResource(R.string.done))
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
