@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.BackgroundColorKey
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.FALLBACK_SOURCE_DEFAULT
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.FallbackSourceKey
import dev.renkinProject.renkin.data.IMAGE_EDIT_DEFAULT
import dev.renkinProject.renkin.data.IconColorKey
import dev.renkinProject.renkin.data.ColorizerGradientAngleKey
import androidx.compose.animation.AnimatedVisibility
import dev.renkinProject.renkin.data.ColorizerGradientColorKey
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_DEFAULT
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MAX
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MIN
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.OutlineColorKey
import dev.renkinProject.renkin.data.OutlineColorizerModeKey
import dev.renkinProject.renkin.data.OutlineGradientAngleKey
import dev.renkinProject.renkin.data.OutlineGradientColorsKey
import dev.renkinProject.renkin.data.OutlineGradientTypeKey
import dev.renkinProject.renkin.data.OutlineWidthKey
import dev.renkinProject.renkin.data.normalizeOutlineWidth
import dev.renkinProject.renkin.data.ColorizerGradientColorsKey
import dev.renkinProject.renkin.data.getGradientStops
import dev.renkinProject.renkin.data.getGradientPositions
import dev.renkinProject.renkin.data.ColorizerGradientPositionsKey
import dev.renkinProject.renkin.data.BackgroundColorizerModeKey
import dev.renkinProject.renkin.data.BackgroundGradientTypeKey
import dev.renkinProject.renkin.data.BackgroundGradientAngleKey
import dev.renkinProject.renkin.data.BackgroundGradientColorsKey
import dev.renkinProject.renkin.data.BackgroundGradientPositionsKey
import dev.renkinProject.renkin.data.BackgroundStyleKeys
import dev.renkinProject.renkin.data.OutlineGradientPositionsKey
import dev.renkinProject.renkin.data.ColorizerStyleKeys
import dev.renkinProject.renkin.data.OutlineStyleKeys
import dev.renkinProject.renkin.data.setColorStyle
import dev.renkinProject.renkin.data.ColorizerGradientTypeKey
import dev.renkinProject.renkin.data.ColorizerModeKey
import dev.renkinProject.renkin.data.getIconColor
import dev.renkinProject.renkin.data.getBackgroundColor
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.IncludeVectorKey
import dev.renkinProject.renkin.data.MonochromeKey
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimaryImageEditKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.PrimaryTextTypeKey
import dev.renkinProject.renkin.data.TextFontKey
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.ColorStyleKeys
import dev.renkinProject.renkin.data.SecondaryIconPackKey
import dev.renkinProject.renkin.data.SecondaryImageEditKey
import dev.renkinProject.renkin.data.SecondarySourceKey
import dev.renkinProject.renkin.data.SecondaryTextTypeKey
import dev.renkinProject.renkin.data.TEXT_TYPE_DEFAULT
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getColorValue
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setColorValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setIntValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The home list's Advanced options card, back in its old place: the expandable generation
 * options plus (for now, while the new screen is being evaluated) a test button that opens
 * the fullscreen Global options screen — the same content is reachable from both.
 */
@Composable
fun AdvancedOptionsCard(iconPacks: List<IconPack>, onOpenGlobal: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = dev.renkinProject.renkin.ui.theme.CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = { expanded = !expanded }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading glyph so the row reads as an interactive control, not a bare list
                // entry — the only tappable element on home without one.
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.advancedOptions),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                val chevronRotation by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "optionsChevron"
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column {
                    // Trial entry point for the fullscreen Global options screen; sits above
                    // the option controls so testers actually find it.
                    androidx.compose.material3.OutlinedButton(
                        onClick = onOpenGlobal,
                        shape = FieldShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.globalScreenButton))
                    }
                    AdvancedOptionsContent(iconPacks)
                }
            }
        }
    }
}

/**
 * The refresh-wide generation options (sources, fallback, colours, switches), shared by the
 * home card above and the Global options screen's panel. Every control writes straight to the
 * profile's DataStore — only the global modifiers on that screen are staged behind Save.
 * [showHint] shows the "takes effect after a refresh" note — true on the home card, false on
 * the Global options screen where the live grid already answers that question.
 */
@Composable
fun AdvancedOptionsContent(
    iconPacks: List<IconPack>,
    showHint: Boolean = true
) {
    val state = advancedOptionsState()

    Column(Modifier.padding(bottom = 12.dp)) {
        // Users otherwise don't know these settings only take effect after a refresh
        if (showHint) RefreshHintCard()
        AdvancedSourceSection(state, iconPacks)
        AdvancedColorSection(state)
        AdvancedOutlineSection(state)
        AdvancedBehaviorSection(state)
    }
}

/**
 * Every preference the advanced options read, resolved once per composition. Sections take this
 * instead of a dozen loose values, which is what let the content split into readable pieces.
 */
private data class AdvancedOptionsState(
    val primarySource: Source,
    val primaryImageEdit: ImageEdit,
    val primaryTextType: TextType,
    val primaryIconPack: String,
    val secondarySource: Source,
    val secondaryImageEdit: ImageEdit,
    val secondaryTextType: TextType,
    val secondaryIconPack: String,
    val useVector: Boolean,
    val useMaterialYou: Boolean,
    val useThemed: Boolean,
    val retrieveCalendarIcons: Boolean,
    val overrideIcon: Boolean,
    val fallbackSource: FallbackSource,
    val iconColor: Color,
    val colorizerStyle: ColorizerStyle,
    val backgroundStyle: ColorizerStyle,
    val outlineStyle: ColorizerStyle,
    val outlineAdd: Boolean,
    val outlineWidth: Float
) {
    val pathTracing: Boolean
        get() = isPathTracingEnabled(
            primarySource, primaryImageEdit, secondarySource, secondaryImageEdit
        )

    /** Colorize replaces the plain icon-colour picker with the full colour style. */
    val showColorizer: Boolean
        get() = (needImageEdit(primarySource) &&
            primaryImageEdit == ImageEdit.COLORIZE) ||
            (needImageEdit(secondarySource) &&
                secondaryImageEdit == ImageEdit.COLORIZE)

    val primaryIsPack: Boolean get() = isIconPackSelected(primarySource, primaryIconPack)
    val secondaryIsPack: Boolean get() = isIconPackSelected(secondarySource, secondaryIconPack)
}

@Composable
private fun advancedOptionsState(): AdvancedOptionsState {
    val prefs = getPreferences()
    val iconColor = prefs.getIconColor()
    return AdvancedOptionsState(
        primarySource = prefs.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT),
        primaryImageEdit = prefs.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT),
        primaryTextType = prefs.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT),
        primaryIconPack = prefs.getStringValue(PrimaryIconPackKey),
        secondarySource = prefs.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT),
        secondaryImageEdit = prefs.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT),
        secondaryTextType = prefs.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT),
        secondaryIconPack = prefs.getStringValue(SecondaryIconPackKey),
        useVector = prefs.getBooleanValue(IncludeVectorKey),
        useMaterialYou = prefs.getBooleanValue(MonochromeKey),
        useThemed = prefs.getBooleanValue(ExportThemedKey),
        retrieveCalendarIcons = prefs.getBooleanValue(CalendarIconsKey),
        overrideIcon = prefs.getBooleanValue(OverrideIconKey),
        fallbackSource = prefs.getEnumValue(FallbackSourceKey, FALLBACK_SOURCE_DEFAULT),
        iconColor = iconColor,
        colorizerStyle = ColorizerStyle(
            mode = prefs.getEnumValue(ColorizerModeKey, ColorizerMode.SINGLE_COLOR),
            gradientType = prefs.getEnumValue(ColorizerGradientTypeKey, GradientType.LINEAR),
            firstColor = iconColor.toArgb(),
            gradientStops = prefs.getGradientStops(
                ColorizerGradientColorsKey, ColorizerGradientColorKey
            ),
            gradientPositions = prefs.getGradientPositions(ColorizerGradientPositionsKey),
            gradientAngle = prefs.getIntValue(ColorizerGradientAngleKey)
                .coerceIn(0, 360).toFloat()
        ),
        backgroundStyle = ColorizerStyle(
            mode = prefs.getEnumValue(BackgroundColorizerModeKey, ColorizerMode.SINGLE_COLOR),
            gradientType = prefs.getEnumValue(BackgroundGradientTypeKey, GradientType.LINEAR),
            firstColor = prefs.getBackgroundColor().toArgb(),
            gradientStops = prefs.getGradientStops(BackgroundGradientColorsKey, BackgroundColorKey),
            gradientPositions = prefs.getGradientPositions(BackgroundGradientPositionsKey),
            gradientAngle = prefs.getIntValue(BackgroundGradientAngleKey)
                .coerceIn(0, 360).toFloat()
        ),
        // Pack-wide outline: the same keys the Global options screen edits, surfaced here so the
        // hero card's Advanced options can turn it on without opening that screen.
        outlineStyle = ColorizerStyle(
            mode = prefs.getEnumValue(OutlineColorizerModeKey, ColorizerMode.SINGLE_COLOR),
            gradientType = prefs.getEnumValue(OutlineGradientTypeKey, GradientType.LINEAR),
            firstColor = prefs.getColorValue(OutlineColorKey, Color.Black).toArgb(),
            gradientStops = prefs.getGradientStops(OutlineGradientColorsKey, OutlineColorKey),
            gradientPositions = prefs.getGradientPositions(OutlineGradientPositionsKey),
            gradientAngle = prefs.getIntValue(OutlineGradientAngleKey).coerceIn(0, 360).toFloat()
        ),
        outlineAdd = prefs.getBooleanValue(OutlineAddKey),
        outlineWidth = normalizeOutlineWidth(
            prefs.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)
        ).toFloat()
    )
}

@Composable
private fun RefreshHintCard() {
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.optionsHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * The primary source/pack itself is picked in the hero card on the home screen; only its tweaks
 * (image modifier, text type, the secondary source and the fallback) live here.
 */
@Composable
private fun AdvancedSourceSection(
    state: AdvancedOptionsState,
    iconPacks: List<IconPack>
) {
    val prefs = getPreferences()
    val scope = rememberCoroutineScope()
    val hasSourceControls = needImageEdit(state.primarySource) ||
        needTextType(state.primarySource) || needTextType(state.secondarySource) ||
        needSecondarySource(state.primarySource) || state.primaryIsPack

    if (hasSourceControls) {
        OptionsSectionLabel(R.string.advancedSectionSource)
    }
    if (needImageEdit(state.primarySource)) {
        ImageEditDropdown(R.string.primaryImageEdit, state.primaryImageEdit) {
            scope.launch { prefs.setEnumValue(PrimaryImageEditKey, it) }
        }
    }

    if (needTextType(state.primarySource)) {
        TextTypeDropdown(R.string.primaryTextType, state.primaryTextType) {
            scope.launch { prefs.setEnumValue(PrimaryTextTypeKey, it) }
        }
    }

    // One shared font for every text icon the refresh generates (per-app override lives in the
    // edit dialog). Shown when any source produces text icons.
    if (needTextType(state.primarySource) || needTextType(state.secondarySource)) {
        val textFont = prefs.getStringValue(TextFontKey)
        Box(Modifier.padding(horizontal = 12.dp)) {
            FontPickerRow(selectedPath = textFont) {
                scope.launch { prefs.setStringValue(TextFontKey, it) }
            }
        }
    }

    if (needSecondarySource(state.primarySource)) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        SourceDropdown(R.string.secondarySource, state.secondarySource) {
            scope.launch { prefs.setEnumValue(SecondarySourceKey, it) }
        }

        if (needIconPack(state.secondarySource)) {
            IconPackDropdown(
                R.string.secondaryIconPack, iconPacks, state.secondaryIconPack, null
            ) { scope.launch { prefs.setStringValue(SecondaryIconPackKey, it.packageName) } }
        }

        if (needImageEdit(state.secondarySource)) {
            ImageEditDropdown(R.string.secondaryImageEdit, state.secondaryImageEdit) {
                scope.launch { prefs.setEnumValue(SecondaryImageEditKey, it) }
            }
        }

        if (needTextType(state.secondarySource)) {
            TextTypeDropdown(R.string.secondaryTextType, state.secondaryTextType) {
                scope.launch { prefs.setEnumValue(SecondaryTextTypeKey, it) }
            }
        }
    }

    if (state.primaryIsPack) {
        RetrieveCalendarIconsSwitch(state.retrieveCalendarIcons) {
            scope.launch { prefs.setBooleanValue(CalendarIconsKey, it) }
        }
    }

    // Fallback styling for apps neither pack themes — only when a pack source exists.
    if (state.primaryIsPack || state.secondaryIsPack) {
        FallbackSourceSelector(
            selected = state.fallbackSource,
            primaryEnabled = state.primaryIsPack,
            secondaryEnabled = state.secondaryIsPack
        ) { scope.launch { prefs.setEnumValue(FallbackSourceKey, it) } }

        val fallbackPack = when (state.fallbackSource) {
            FallbackSource.PRIMARY -> state.primaryIconPack
            FallbackSource.SECONDARY -> state.secondaryIconPack
            FallbackSource.NONE -> ""
        }
        if (state.fallbackSource != FallbackSource.NONE && fallbackPack.isNotEmpty()) {
            FallbackPreview(state.fallbackSource, fallbackPack)
        }
    }
}

/** Icon colour (a full style while Colorize is on) and the pack-wide background. */
@Composable
private fun AdvancedColorSection(state: AdvancedOptionsState) {
    val prefs = getPreferences()
    val scope = rememberCoroutineScope()
    var colorizeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var backgroundSheetOpen by rememberSaveable { mutableStateOf(false) }
    val showIconColor = showIconColor(
        state.primarySource, state.primaryImageEdit,
        state.secondarySource, state.secondaryImageEdit, state.useThemed
    )
    val showBgColor = showBackgroundColor(
        state.primarySource, state.primaryImageEdit,
        state.secondarySource, state.secondaryImageEdit, state.useThemed
    )

    if (showIconColor || showBgColor) {
        OptionsSectionLabel(R.string.advancedSectionColors)
    }
    if (showIconColor) {
        if (state.showColorizer) {
            ColorStyleCard(
                label = stringResource(R.string.colorize),
                style = state.colorizerStyle,
                onClick = { colorizeSheetOpen = true }
            )
            if (colorizeSheetOpen) {
                ColorStyleSheet(
                    title = stringResource(R.string.colorize),
                    initialStyle = state.colorizerStyle,
                    sampleBitmap = null,
                    showSingleColorEffects = false,
                    onDismiss = { colorizeSheetOpen = false },
                    onApply = { style ->
                        colorizeSheetOpen = false
                        scope.launch { prefs.persistColorStyle(ColorizerStyleKeys, style) }
                    }
                )
            }
        } else {
            ColorButton(stringResource(R.string.iconColor), state.iconColor) {
                scope.launch { prefs.setColorValue(IconColorKey, it) }
            }
        }
    }
    if (showBgColor) {
        ColorStyleCard(
            label = stringResource(R.string.backgroundColor),
            style = state.backgroundStyle,
            onClick = { backgroundSheetOpen = true }
        )
        if (backgroundSheetOpen) {
            ColorStyleSheet(
                title = stringResource(R.string.backgroundColor),
                initialStyle = state.backgroundStyle,
                sampleBitmap = null,
                // Solid fill / monochrome / inverse describe artwork, and a background has
                // none — it is the fill itself.
                showSingleColorEffects = false,
                onDismiss = { backgroundSheetOpen = false },
                onApply = { style ->
                    backgroundSheetOpen = false
                    scope.launch { prefs.persistColorStyle(BackgroundStyleKeys, style) }
                }
            )
        }
    }
}

/** The contour drawn around every generated icon, and the colour style it is drawn with. */
@Composable
private fun AdvancedOutlineSection(state: AdvancedOptionsState) {
    val prefs = getPreferences()
    val scope = rememberCoroutineScope()
    var outlineSheetOpen by rememberSaveable { mutableStateOf(false) }

    OptionsSectionLabel(R.string.outlineTitle)
    OutlineSwitch(state.outlineAdd) {
        scope.launch { prefs.setBooleanValue(OutlineAddKey, it) }
    }
    AnimatedVisibility(visible = state.outlineAdd) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledSlider(
                label = stringResource(R.string.outlineThickness),
                value = state.outlineWidth,
                onValueChange = {
                    scope.launch { prefs.setIntValue(OutlineWidthKey, it.roundToInt()) }
                },
                valueRange = OUTLINE_WIDTH_MIN.toFloat()..OUTLINE_WIDTH_MAX.toFloat(),
                valueLabel = "${state.outlineWidth.roundToInt()} px",
                // The card's other rows are inset by the same amount; without it the track ran
                // past the card's rounded edge.
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            ColorStyleCard(
                label = stringResource(R.string.outlineColor),
                style = state.outlineStyle,
                onClick = { outlineSheetOpen = true }
            )
            if (outlineSheetOpen) {
                ColorStyleSheet(
                    title = stringResource(R.string.outlineColor),
                    initialStyle = state.outlineStyle,
                    sampleBitmap = null,
                    showSingleColorEffects = false,
                    onDismiss = { outlineSheetOpen = false },
                    onApply = { style ->
                        outlineSheetOpen = false
                        scope.launch { prefs.persistColorStyle(OutlineStyleKeys, style) }
                    }
                )
            }
        }
    }
}

/** What a refresh is allowed to touch, and how the icons are exported. */
@Composable
private fun AdvancedBehaviorSection(state: AdvancedOptionsState) {
    val prefs = getPreferences()
    val scope = rememberCoroutineScope()

    OptionsSectionLabel(R.string.advancedSectionBehavior)
    OverrideIconSwitch(state.overrideIcon) {
        scope.launch { prefs.setBooleanValue(OverrideIconKey, it) }
    }

    if (state.pathTracing) {
        VectorSwitch(state.useVector) {
            scope.launch { prefs.setBooleanValue(IncludeVectorKey, it) }
        }
        MaterialYouSwitch(state.useMaterialYou) {
            scope.launch { prefs.setBooleanValue(MonochromeKey, it) }
        }
    }

    ThemedIconsSwitch(state.useThemed) {
        scope.launch { prefs.setBooleanValue(ExportThemedKey, it) }
    }
}

/** The three colour sheets all commit the same way; the ordinals belong in one place. */
private suspend fun DataStore<Preferences>.persistColorStyle(
    keys: ColorStyleKeys,
    style: ColorizerStyle
) =
    setColorStyle(
        keys,
        mode = style.mode.ordinal,
        gradientType = style.gradientType.ordinal,
        gradientAngle = style.gradientAngle.roundToInt(),
        firstColor = Color(style.firstColor),
        gradientStops = style.gradientStops,
        gradientPositions = style.gradientPositions
    )

/** Small section label splitting the advanced options into readable groups. */
@Composable
private fun OptionsSectionLabel(@androidx.annotation.StringRes id: Int) {
    Text(
        text = stringResource(id),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp)
    )
}

/**
 * Segmented selector for which pack's fallback styling unthemed apps inherit (None / Primary /
 * Secondary). Primary/Secondary are disabled unless that source is a configured icon pack.
 */
@Composable
private fun FallbackSourceSelector(
    selected: FallbackSource,
    primaryEnabled: Boolean,
    secondaryEnabled: Boolean,
    onChange: (FallbackSource) -> Unit
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.fallbackStyling),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        val disabledHint = stringResource(R.string.fallbackDisabledHint)
        SegmentedRow {
            SegmentCell(stringResource(R.string.fallbackNone), selected == FallbackSource.NONE, Modifier.weight(1f)) { onChange(FallbackSource.NONE) }
            SegmentCell(stringResource(R.string.fallbackPrimary), selected == FallbackSource.PRIMARY, Modifier.weight(1f), primaryEnabled, disabledHint) { onChange(FallbackSource.PRIMARY) }
            SegmentCell(stringResource(R.string.fallbackSecondary), selected == FallbackSource.SECONDARY, Modifier.weight(1f), secondaryEnabled, disabledHint) { onChange(FallbackSource.SECONDARY) }
        }
    }
}


/**
 * Live sample of the chosen fallback styling applied to a few of the user's app icons, so the
 * uniform frame is visible before building. Keyed on the source + pack, so it refreshes when the
 * user switches Primary/Secondary or changes the pack.
 */
@Composable
private fun FallbackPreview(fallbackSource: FallbackSource, fallbackPack: String) {
    val vm = hiltViewModel<MainViewModel>()
    val preferences = getPreferences().getPreferencesValue()
    val previews by produceState<List<IconPackDrawable>>(emptyList(), fallbackSource, fallbackPack, preferences) {
        value = vm.fallbackPreview(preferences, fallbackSource)
    }
    if (previews.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.fallbackPreviewLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            previews.forEach { icon ->
                Image(
                    painter = icon.getPainter(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(InnerShape)
                )
            }
        }
    }
}
