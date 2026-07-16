@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.GlobalApplyCustomKey
import dev.renkinProject.renkin.data.GlobalColorizeColorKey
import dev.renkinProject.renkin.data.GlobalColorizeFlatKey
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalIncludeEmptyKey
import dev.renkinProject.renkin.data.GlobalShapeColorKey
import dev.renkinProject.renkin.data.GlobalShapeCropKey
import dev.renkinProject.renkin.data.GlobalShapeKey
import dev.renkinProject.renkin.data.GlobalShapeScaleKey
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_DEFAULT
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.OutlineColorKey
import dev.renkinProject.renkin.data.OutlineWidthKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TEXT_TYPE_DEFAULT
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getColorValue
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.normalizeGlobalScalePercent
import dev.renkinProject.renkin.data.normalizeOutlineWidth
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.IconShape as IconTileShape
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Global options screen's staged modifier values. Nothing here touches DataStore until
 * Save — the grid below previews these values live, and Back discards them (after a prompt).
 */
@Stable
internal class GlobalModifierState {
    var shape by mutableStateOf(IconShape.NONE)
    var shapeCrop by mutableStateOf(true)
    var shapeScale by mutableFloatStateOf(1f)
    var shapeColor by mutableStateOf(Color.White)
    var iconScale by mutableFloatStateOf(1f)
    var outlineAdd by mutableStateOf(false)
    var outlineWidth by mutableFloatStateOf(OUTLINE_WIDTH_DEFAULT.toFloat())
    var outlineColor by mutableStateOf(Color.Black)
    var colorize by mutableStateOf(false)
    var colorizeColor by mutableStateOf(Color.White)
    var colorizeFlat by mutableStateOf(false)
    var applyToCustom by mutableStateOf(false)
    var includeEmpty by mutableStateOf(false)

    /** True when the staged values would visibly change an icon at all. */
    val hasAnyEffect: Boolean
        get() = shape != IconShape.NONE || iconScale != 1f || outlineAdd || colorize

    fun seedFrom(preferences: Preferences) {
        shape = IconShape.entries.getOrElse(
            preferences.getIntValue(GlobalShapeKey, IconShape.NONE.ordinal)
        ) { IconShape.NONE }
        shapeCrop = preferences.getBooleanValue(GlobalShapeCropKey, true)
        shapeScale = normalizeGlobalScalePercent(preferences.getIntValue(GlobalShapeScaleKey, 100)) / 100f
        shapeColor = preferences.getColorValue(GlobalShapeColorKey, Color.White)
        iconScale = normalizeGlobalScalePercent(preferences.getIntValue(GlobalIconScaleKey, 100)) / 100f
        outlineAdd = preferences.getBooleanValue(OutlineAddKey)
        outlineWidth = normalizeOutlineWidth(
            preferences.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)).toFloat()
        outlineColor = preferences.getColorValue(OutlineColorKey, Color.Black)
        colorize = preferences.getBooleanValue(GlobalColorizeKey)
        colorizeColor = preferences.getColorValue(GlobalColorizeColorKey, Color.White)
        colorizeFlat = preferences.getBooleanValue(GlobalColorizeFlatKey)
        applyToCustom = preferences.getBooleanValue(GlobalApplyCustomKey)
        includeEmpty = preferences.getBooleanValue(GlobalIncludeEmptyKey)
    }

    /** All values as one comparable list — the screen's dirty check against its baseline. */
    fun snapshot(): List<Any> = listOf(
        shape.ordinal, shapeCrop, (shapeScale * 100).roundToInt(), shapeColor.toArgb(),
        (iconScale * 100).roundToInt(), outlineAdd, outlineWidth.roundToInt(),
        outlineColor.toArgb(), colorize, colorizeColor.toArgb(), colorizeFlat,
        applyToCustom, includeEmpty
    )

    /** Writes the staged values into [mutable] — shared by [persist] and the empty-slot preview. */
    private fun writeInto(mutable: androidx.datastore.preferences.core.MutablePreferences) {
        mutable[GlobalShapeKey] = shape.ordinal
        mutable[GlobalShapeCropKey] = shapeCrop
        mutable[GlobalShapeScaleKey] = (shapeScale * 100).roundToInt()
        mutable[GlobalShapeColorKey] = shapeColor.toHexString()
        mutable[GlobalIconScaleKey] = (iconScale * 100).roundToInt()
        mutable[OutlineAddKey] = outlineAdd
        mutable[OutlineWidthKey] = outlineWidth.roundToInt()
        mutable[OutlineColorKey] = outlineColor.toHexString()
        mutable[GlobalColorizeKey] = colorize
        mutable[GlobalColorizeColorKey] = colorizeColor.toHexString()
        mutable[GlobalColorizeFlatKey] = colorizeFlat
        mutable[GlobalApplyCustomKey] = applyToCustom
        mutable[GlobalIncludeEmptyKey] = includeEmpty
    }

    suspend fun persist(store: DataStore<Preferences>) {
        store.edit { writeInto(it) }
    }

    /**
     * A preferences snapshot with the STAGED values overlaid, so the empty-slot preview and
     * a real refresh after Save produce exactly the same icons ([GenerationOptions
     * .fromPreferences] reads the global keys).
     */
    fun overlay(preferences: Preferences): Preferences =
        preferences.toMutablePreferences().also { writeInto(it) }

    /**
     * Modifier-only options for previewing/baking over an EXISTING icon: colorize as the image
     * edit (when on), plus scale, shape and outline. Sources are irrelevant to applyModifier.
     */
    fun toModifierOptions(): GenerationOptions = GenerationOptions(
        primarySource = Source.NONE,
        primaryImageEdit = if (colorize) ImageEdit.COLORIZE else ImageEdit.NONE,
        primaryTextType = TEXT_TYPE_DEFAULT,
        primaryIconPack = "",
        color = colorizeColor.toArgb(),
        bgColor = if (shape != IconShape.NONE && !shapeCrop) shapeColor.toArgb()
            else android.graphics.Color.TRANSPARENT,
        vector = false,
        materialYou = false,
        themed = false,
        override = true,
        colorizeFlat = colorizeFlat,
        iconScale = iconScale,
        iconShape = shape,
        iconShapeCrop = shapeCrop,
        iconShapeScale = shapeScale,
        outlineMode = if (outlineAdd) OutlineMode.ADD else OutlineMode.NONE,
        outlineWidth = outlineWidth,
        outlineColor = outlineColor.toArgb()
    )
}

/** The home-list entry card that opens the fullscreen [GlobalOptionsScreen]. */
@Composable
fun GlobalOptionsCard(onOpen: () -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.globalOptionsTitle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.globalOptionsEntryHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Fullscreen Global options: the refresh-wide generation options (immediate, like the old
 * Advanced options card), the staged global modifiers, and a live preview grid of every icon
 * split into generated / custom / iconless apps. Save bakes the modifiers into the icons and
 * persists them; tapping a tile opens a modifiers-only per-app editor.
 */
@Composable
fun GlobalOptionsScreen(iconPacks: List<IconPack>, onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val prefs = getPreferences()
    val prefsValue = prefs.getPreferencesValue()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val state = remember { GlobalModifierState() }
    // The baseline snapshot the dirty check compares against; null until seeding completes.
    var baseline by remember { mutableStateOf<List<Any>?>(null) }
    LaunchedEffect(Unit) {
        state.seedFrom(prefs.data.first())
        baseline = state.snapshot()
    }

    val applying = viewModel.globalApplyProgress != null
    val dirty = baseline != null && baseline != state.snapshot()
    var confirmDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (applying) Unit
        else if (dirty) confirmDiscard = true
        else onDismiss()
    }

    // Staged modifiers → the preview options. null = nothing to apply (tiles show the icon
    // as-is without spinning up a generation per tile).
    val tileOptions = if (state.hasAnyEffect) state.toModifierOptions() else null
    // Empty slots preview a full generation with the staged globals overlaid, so what's shown
    // matches what Save (and a later refresh) actually produces.
    val emptyOptions = if (state.includeEmpty) {
        GenerationOptions.fromPreferences(state.overlay(prefsValue), context, override = true)
    } else null

    val apps = viewModel.applicationList
    val locked = viewModel.lockedIconKeys
    val generated = apps.filter { it.createdIcon != null && !it.isCustom }
    val custom = apps.filter { it.createdIcon != null && it.isCustom }
    val iconless = apps.filter { it.createdIcon == null && it.key !in locked }

    var editApp by remember { mutableStateOf<PackageInfoStruct?>(null) }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = close, enabled = !applying) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = stringResource(R.string.globalOptionsTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            view.performConfirmHaptic()
                            scope.launch {
                                state.persist(prefs)
                                viewModel.applyGlobalModifiers(
                                    state.toModifierOptions(), state.applyToCustom, state.includeEmpty
                                ) { baseline = state.snapshot() }
                            }
                        },
                        enabled = !applying,
                        shape = dev.renkinProject.renkin.ui.theme.FieldShape
                    ) {
                        Text(stringResource(if (applying) R.string.globalApplying else R.string.save))
                    }
                }
                HorizontalDivider()
                viewModel.globalApplyProgress?.let { (done, total) ->
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { done / total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        WavyLoadingBar(Modifier.fillMaxWidth())
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(84.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "advanced", span = { GridItemSpan(maxLineSpan) }) {
                        AdvancedOptionsSection(iconPacks)
                    }
                    item(key = "modifiers", span = { GridItemSpan(maxLineSpan) }) {
                        GlobalModifierControls(state)
                    }
                    item(key = "toggles", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                ControlledSwitchRow(
                                    label = stringResource(R.string.globalApplyCustom),
                                    checked = state.applyToCustom
                                ) { state.applyToCustom = it }
                                ControlledSwitchRow(
                                    label = stringResource(R.string.globalIncludeEmpty),
                                    checked = state.includeEmpty
                                ) { state.includeEmpty = it }
                            }
                        }
                    }

                    if (generated.isEmpty() && custom.isEmpty() && (!state.includeEmpty || iconless.isEmpty())) {
                        item(key = "noIcons", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                icon = Icons.Filled.ImageSearch,
                                text = stringResource(R.string.globalNoIcons),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp)
                            )
                        }
                    }

                    if (generated.isNotEmpty()) {
                        item(key = "genHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalGeneratedSection, generated.size),
                                hint = androidx.compose.ui.text.AnnotatedString(stringResource(R.string.globalGridHint))
                            )
                        }
                        items(generated, key = { "g/${it.key}" }) { app ->
                            IconPreviewTile(app, tileOptions, viewModel) { editApp = app }
                        }
                    }

                    if (custom.isNotEmpty()) {
                        item(key = "customHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalCustomSection, custom.size),
                                hint = if (state.applyToCustom) null
                                    else boldStringResource(R.string.globalCustomHint),
                                divider = true
                            )
                        }
                        items(custom, key = { "c/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (state.applyToCustom) tileOptions else null,
                                viewModel,
                                showEditBadge = true
                            ) { editApp = app }
                        }
                    }

                    if (state.includeEmpty && iconless.isNotEmpty()) {
                        item(key = "emptyHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalEmptySection, iconless.size),
                                hint = androidx.compose.ui.text.AnnotatedString(stringResource(R.string.globalEmptyHint)),
                                divider = true
                            )
                        }
                        items(iconless, key = { "e/${it.key}" }) { app ->
                            GeneratedPreviewTile(app, emptyOptions, viewModel) { editApp = app }
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.globalDiscardTitle),
            text = stringResource(R.string.globalDiscardText),
            onConfirm = {
                confirmDiscard = false
                onDismiss()
            },
            onDismiss = { confirmDiscard = false }
        )
    }

    editApp?.let { app ->
        GlobalIconEditDialog(app = app, onDismiss = { editApp = null })
    }
}

/** The old Advanced options card content, collapsed by default inside its own card. */
@Composable
private fun AdvancedOptionsSection(iconPacks: List<IconPack>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { expanded = !expanded })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.advancedOptions),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "advancedChevron"
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
            AnimatedVisibility(visible = expanded) {
                AdvancedOptionsContent(iconPacks)
            }
        }
    }
}

/** The staged global modifier controls: shape, icon scale, outline and colorize. */
@Composable
private fun GlobalModifierControls(state: GlobalModifierState) {
    var shapeColorPickerOpen by remember { mutableStateOf(false) }
    var outlineColorPickerOpen by remember { mutableStateOf(false) }
    var colorizeColorPickerOpen by remember { mutableStateOf(false) }

    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.globalModifiersTitle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.globalModifiersHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Shape — same swatches and controls as the per-app Modifier tab.
            Text(
                text = stringResource(R.string.iconShapeTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        valueLabel = "${(state.shapeScale * 100).roundToInt()}%",
                        centered = true
                    )
                    if (!state.shapeCrop) {
                        ColorRow(stringResource(R.string.shapeColor), state.shapeColor) {
                            shapeColorPickerOpen = true
                        }
                    }
                }
            }

            LabeledSlider(
                label = stringResource(R.string.iconScale),
                value = state.iconScale,
                onValueChange = { state.iconScale = it },
                valueRange = 0.5f..1.5f,
                valueLabel = "${(state.iconScale * 100).roundToInt()}%",
                centered = true
            )

            ControlledSwitchRow(
                label = stringResource(R.string.outlineGlobal),
                checked = state.outlineAdd,
                horizontalPadding = 4.dp
            ) { state.outlineAdd = it }
            AnimatedVisibility(visible = state.outlineAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.outlineThickness),
                        value = state.outlineWidth,
                        onValueChange = { state.outlineWidth = it },
                        valueRange = 1f..16f,
                        valueLabel = "${state.outlineWidth.roundToInt()} px"
                    )
                    ColorRow(stringResource(R.string.outlineColor), state.outlineColor) {
                        outlineColorPickerOpen = true
                    }
                }
            }

            ControlledSwitchRow(
                label = stringResource(R.string.globalColorize),
                checked = state.colorize,
                horizontalPadding = 4.dp
            ) { state.colorize = it }
            AnimatedVisibility(visible = state.colorize) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorRow(stringResource(R.string.iconColor), state.colorizeColor) {
                        colorizeColorPickerOpen = true
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
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
                            checked = state.colorizeFlat,
                            onCheckedChange = { state.colorizeFlat = it }
                        )
                    }
                }
            }
        }
    }

    if (shapeColorPickerOpen) {
        ColorDialog(
            onDismiss = { shapeColorPickerOpen = false },
            currentlySelected = state.shapeColor,
            onColorSelected = { state.shapeColor = it }
        )
    }
    if (outlineColorPickerOpen) {
        ColorDialog(
            onDismiss = { outlineColorPickerOpen = false },
            currentlySelected = state.outlineColor,
            onColorSelected = { state.outlineColor = it }
        )
    }
    if (colorizeColorPickerOpen) {
        ColorDialog(
            onDismiss = { colorizeColorPickerOpen = false },
            currentlySelected = state.colorizeColor,
            onColorSelected = { state.colorizeColor = it }
        )
    }
}

/** A colour-picking row: label plus the current colour as a tappable swatch. */
@Composable
private fun ColorRow(label: String, color: Color, onClick: () -> Unit) {
    OptionCard(
        label = label,
        onClick = onClick,
        trailing = {
            Surface(
                shape = CircleShape,
                color = color,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(28.dp)
            ) {}
        }
    )
}

/** A switch row bound directly to caller state (unlike DefaultSwitchLayout's remembered copy). */
@Composable
private fun ControlledSwitchRow(
    label: String,
    checked: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A grid section title with an optional hint line and an optional divider above. */
@Composable
private fun SectionHeader(
    title: String,
    hint: androidx.compose.ui.text.AnnotatedString? = null,
    divider: Boolean = false
) {
    Column(Modifier.padding(top = if (divider) 6.dp else 2.dp)) {
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * One preview tile for an app that already has an icon: shows the icon with [options]
 * applied (or as-is when null), the app name, and an edit badge for custom icons.
 */
@Composable
private fun IconPreviewTile(
    app: PackageInfoStruct,
    options: GenerationOptions?,
    viewModel: MainViewModel,
    showEditBadge: Boolean = false,
    onClick: () -> Unit
) {
    val base = app.createdIcon
    // Recomputes when the staged modifiers change; shows the unmodified icon meanwhile.
    val preview by produceState(base, base, options) {
        value = if (base != null && options != null) {
            withContext(Dispatchers.Default) {
                runCatching { viewModel.applyModifier(base, options) }.getOrDefault(base)
            }
        } else base
    }
    PreviewTileFrame(app, showEditBadge, onClick) {
        val icon = preview
        if (icon != null) {
            Image(
                painter = icon.getPainter(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        }
    }
}

/**
 * A tile for an app with no created icon yet: previews the icon a generation with the staged
 * globals would produce. Until it arrives (or when generation yields nothing), the app's own
 * launcher icon shows dimmed as the placeholder.
 */
@Composable
private fun GeneratedPreviewTile(
    app: PackageInfoStruct,
    options: GenerationOptions?,
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    val preview by produceState<IconPackDrawable?>(null, app.key, options) {
        value = if (options == null) null else withContext(Dispatchers.Default) {
            runCatching { viewModel.previewIcon(app, options, null) }.getOrNull()
        }
    }
    PreviewTileFrame(app, showEditBadge = false, onClick = onClick) {
        val icon = preview
        if (icon != null) {
            Image(
                painter = icon.getPainter(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        } else {
            val fallback = rememberAppBitmap(app)
            if (fallback != null) {
                Image(
                    painter = BitmapPainter(fallback),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(IconTileShape)
                        .alpha(0.35f)
                )
            }
        }
    }
}

/** The shared tile chrome: icon slot on top, single-line app name below, optional edit badge. */
@Composable
private fun PreviewTileFrame(
    app: PackageInfoStruct,
    showEditBadge: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(Modifier.size(56.dp)) {
            iconContent()
            if (showEditBadge) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp),
                    shape = SwatchShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )
    }
}

/**
 * The modifiers-only per-app editor opened from the grid: the full Modifier tab over the
 * app's current icon with a before/after preview. Apply stores the result as a custom icon
 * (a refresh won't replace it), exactly like the main edit dialog's Apply.
 */
@Composable
private fun GlobalIconEditDialog(app: PackageInfoStruct, onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val externalEditorError = stringResource(R.string.noImageEditorAvailable)

    var imageEdit by remember { mutableStateOf(ImageEdit.NONE) }
    var iconColor by remember { mutableStateOf(Color.White) }
    var useVector by remember { mutableStateOf(false) }
    val adjustments = remember { AdjustmentState() }

    // The icon the modifiers act on: the stored icon, or the app's own launcher icon for
    // apps that don't have one yet.
    val heroBitmap = remember(app) {
        runCatching { app.icon.toSafeBitmapOrNull() }.getOrNull()
    }
    val base = remember(app) {
        app.createdIcon ?: heroBitmap?.let { BitmapIconDrawable(it, false) }
    }

    val options = GenerationOptions(
        primarySource = Source.APPLICATION_ICON,
        primaryImageEdit = imageEdit,
        primaryTextType = TEXT_TYPE_DEFAULT,
        primaryIconPack = "",
        color = iconColor.toArgb(),
        bgColor = if (adjustments.iconShape != IconShape.NONE && !adjustments.shapeCrop) {
            adjustments.shapeColor.toArgb()
        } else android.graphics.Color.TRANSPARENT,
        vector = useVector,
        materialYou = false,
        themed = false,
        override = true,
        edgeLowThreshold = adjustments.edgeThreshold,
        edgeHighThreshold = adjustments.edgeThreshold * 3f,
        edgeGaussianRadius = adjustments.edgeSmoothing,
        edgeContrastNormalized = adjustments.edgeContrast,
        iconScale = adjustments.iconScale,
        bgRemovalTolerance = adjustments.bgRemovalTolerance,
        iconOffsetX = adjustments.iconOffsetX,
        iconOffsetY = adjustments.iconOffsetY,
        colorizeFlat = adjustments.colorizeFlat,
        iconShape = adjustments.iconShape,
        iconShapeCrop = adjustments.shapeCrop,
        iconShapeScale = adjustments.shapeScale,
        outlineMode = adjustments.outlineMode,
        outlineWidth = adjustments.outlineWidth,
        outlineColor = adjustments.outlineColor.toArgb(),
        outlineEraseMask = remember(adjustments.eraseStrokes) {
            if (adjustments.eraseStrokes.isEmpty()) null else buildEraseMask(adjustments.eraseStrokes)
        }
    )

    var preview by remember { mutableStateOf(base) }
    var generating by remember { mutableStateOf(false) }
    // Skip the very first pass: an untouched run through applyModifier would needlessly
    // rasterise a stored vector icon, and Apply must stay disabled until an actual edit.
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(options) {
        val source = base ?: return@LaunchedEffect
        if (!touched) {
            touched = true
            preview = source
            return@LaunchedEffect
        }
        generating = true
        preview = withContext(Dispatchers.Default) {
            runCatching { viewModel.applyModifier(source, options) }.getOrDefault(source)
        }
        generating = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            view.performConfirmHaptic()
                            preview?.let {
                                viewModel.applyIcon(app, it, sourcePackName = app.sourcePackName)
                            }
                            onDismiss()
                        },
                        enabled = preview != null && !generating && preview !== base,
                        shape = dev.renkinProject.renkin.ui.theme.FieldShape
                    ) {
                        Text(stringResource(R.string.apply))
                    }
                }
                HorizontalDivider()

                // Before/after strip so the modifier's effect is visible while scrolled deep
                // into the controls below.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(56.dp)) {
                        base?.let {
                            Image(
                                painter = it.getPainter(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(IconTileShape)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        preview?.let {
                            Image(
                                painter = it.getPainter(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(IconTileShape)
                            )
                        }
                        if (generating) {
                            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()

                ModifierTab(
                    source = Source.APPLICATION_ICON,
                    imageEdit = imageEdit,
                    iconColor = iconColor,
                    useVector = useVector,
                    useMaterialYou = false,
                    adjustments = adjustments,
                    centerPreview = remember(preview) { preview?.toBitmap() },
                    previewGenerating = generating,
                    sampleBitmap = heroBitmap,
                    onImageEditChange = { imageEdit = it },
                    onColorChange = { iconColor = it },
                    onVectorChange = { useVector = it },
                    onMaterialYouChange = { },
                    onEditExternally = { toolbox ->
                        val icon = preview
                        if (icon != null) {
                            scope.launch {
                                val bitmap = withContext(Dispatchers.Default) { icon.toBitmap() }
                                val opened = if (toolbox) openInImageToolbox(context, bitmap)
                                    else editInAnotherApp(context, bitmap)
                                if (!opened) toaster.show(externalEditorError)
                            }
                        }
                    }
                )
            }
        }
    }
}
