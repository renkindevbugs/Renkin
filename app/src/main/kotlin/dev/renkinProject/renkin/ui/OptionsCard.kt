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
import androidx.compose.ui.unit.dp
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
import dev.renkinProject.renkin.data.SecondaryIconPackKey
import dev.renkinProject.renkin.data.SecondaryImageEditKey
import dev.renkinProject.renkin.data.SecondarySourceKey
import dev.renkinProject.renkin.data.SecondaryTextTypeKey
import dev.renkinProject.renkin.data.TEXT_TYPE_DEFAULT
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setColorValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.drawable.IconPackDrawable
import kotlinx.coroutines.launch

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
    val prefs = getPreferences()

    var primarySource by rememberSaveable { mutableStateOf(SOURCE_DEFAULT) }
    var primaryImageEdit by rememberSaveable { mutableStateOf(IMAGE_EDIT_DEFAULT) }
    var primaryTextType by rememberSaveable { mutableStateOf(TEXT_TYPE_DEFAULT) }
    var primaryIconPack by rememberSaveable { mutableStateOf("") }
    var secondarySource by rememberSaveable { mutableStateOf(SOURCE_DEFAULT) }
    var secondaryImageEdit by rememberSaveable { mutableStateOf(IMAGE_EDIT_DEFAULT) }
    var secondaryTextType by rememberSaveable { mutableStateOf(TEXT_TYPE_DEFAULT) }
    var secondaryIconPack by rememberSaveable { mutableStateOf("") }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMaterialYou by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }
    var retrieveCalendarIcons by rememberSaveable { mutableStateOf(false) }
    var overrideIcon by rememberSaveable { mutableStateOf(false) }
    var fallbackSource by rememberSaveable { mutableStateOf(FALLBACK_SOURCE_DEFAULT) }
    val currentColor = prefs.getIconColor()
    val currentBgColor = prefs.getBackgroundColor()

    primarySource = prefs.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
    primaryImageEdit = prefs.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
    primaryTextType = prefs.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT)
    primaryIconPack = prefs.getStringValue(PrimaryIconPackKey)
    secondarySource = prefs.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
    secondaryImageEdit = prefs.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
    secondaryTextType = prefs.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT)
    secondaryIconPack = prefs.getStringValue(SecondaryIconPackKey)
    useVector = prefs.getBooleanValue(IncludeVectorKey)
    useMaterialYou = prefs.getBooleanValue(MonochromeKey)
    useThemed = prefs.getBooleanValue(ExportThemedKey)
    retrieveCalendarIcons = prefs.getBooleanValue(CalendarIconsKey)
    overrideIcon = prefs.getBooleanValue(OverrideIconKey)
    fallbackSource = prefs.getEnumValue(FallbackSourceKey, FALLBACK_SOURCE_DEFAULT)

    val pathTracing = isPathTracingEnabled(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit)
    val showIconColor = showIconColor(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit, useThemed)
    val showBgColor = showBackgroundColor(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit, useThemed)

    val scope = rememberCoroutineScope()

    Column(Modifier.padding(bottom = 12.dp)) {
                // Users otherwise don't know these settings only take effect after a refresh
                if (showHint) {
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

                // The primary source/pack itself is picked in the hero card on the home screen;
                // only its tweaks (image modifier, text type) live here.
                val hasSourceControls = needImageEdit(primarySource) || needTextType(primarySource) ||
                    needTextType(secondarySource) || needSecondarySource(primarySource) ||
                    isIconPackSelected(primarySource, primaryIconPack)
                if (hasSourceControls) {
                    OptionsSectionLabel(R.string.advancedSectionSource)
                }
                if (needImageEdit(primarySource)) {
                    ImageEditDropdown(R.string.primaryImageEdit, primaryImageEdit) { scope.launch { prefs.setEnumValue(
                        PrimaryImageEditKey, it) } }
                }

                if (needTextType(primarySource)) {
                    TextTypeDropdown(R.string.primaryTextType, primaryTextType) { scope.launch { prefs.setEnumValue(
                        PrimaryTextTypeKey, it) } }
                }

                // One shared font for every text icon the refresh generates (per-app override
                // lives in the edit dialog). Shown when any source produces text icons.
                if (needTextType(primarySource) || needTextType(secondarySource)) {
                    val textFont = prefs.getStringValue(TextFontKey)
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        FontPickerRow(selectedPath = textFont) { scope.launch { prefs.setStringValue(TextFontKey, it) } }
                    }
                }

                if (needSecondarySource(primarySource)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    SourceDropdown(R.string.secondarySource, secondarySource) { scope.launch { prefs.setEnumValue(
                        SecondarySourceKey, it) } }

                    if (needIconPack(secondarySource)) {
                        IconPackDropdown(R.string.secondaryIconPack, iconPacks, secondaryIconPack, null) { scope.launch { prefs.setStringValue(
                            SecondaryIconPackKey, it.packageName) } }
                    }

                    if (needImageEdit(secondarySource)) {
                        ImageEditDropdown(R.string.secondaryImageEdit, secondaryImageEdit) { scope.launch { prefs.setEnumValue(
                            SecondaryImageEditKey, it) } }
                    }

                    if (needTextType(secondarySource)) {
                        TextTypeDropdown(R.string.secondaryTextType, secondaryTextType) { scope.launch { prefs.setEnumValue(
                            SecondaryTextTypeKey, it) } }
                    }
                }

                if (isIconPackSelected(primarySource, primaryIconPack)) {
                    RetrieveCalendarIconsSwitch(retrieveCalendarIcons) { scope.launch { prefs.setBooleanValue(
                        CalendarIconsKey, it) } }
                }

                // Fallback styling for apps neither pack themes — only when a pack source exists.
                val primaryIsPack = isIconPackSelected(primarySource, primaryIconPack)
                val secondaryIsPack = isIconPackSelected(secondarySource, secondaryIconPack)
                if (primaryIsPack || secondaryIsPack) {
                    FallbackSourceSelector(
                        selected = fallbackSource,
                        primaryEnabled = primaryIsPack,
                        secondaryEnabled = secondaryIsPack
                    ) { scope.launch { prefs.setEnumValue(FallbackSourceKey, it) } }

                    val fallbackPack = when (fallbackSource) {
                        FallbackSource.PRIMARY -> primaryIconPack
                        FallbackSource.SECONDARY -> secondaryIconPack
                        FallbackSource.NONE -> ""
                    }
                    if (fallbackSource != FallbackSource.NONE && fallbackPack.isNotEmpty()) {
                        FallbackPreview(fallbackSource, fallbackPack)
                    }
                }

                if (showIconColor || showBgColor) {
                    OptionsSectionLabel(R.string.advancedSectionColors)
                }
                if (showIconColor) {
                    ColorButton(stringResource(R.string.iconColor), currentColor) { scope.launch { prefs.setColorValue(
                        IconColorKey, it) } }
                }
                if (showBgColor) {
                    ColorButton(stringResource(R.string.backgroundColor), currentBgColor) { scope.launch { prefs.setColorValue(
                        BackgroundColorKey, it) } }
                }

                OptionsSectionLabel(R.string.advancedSectionBehavior)
                OverrideIconSwitch(overrideIcon) { scope.launch { prefs.setBooleanValue(
                    OverrideIconKey, it) } }

                if (pathTracing) {
                    VectorSwitch(useVector) { scope.launch { prefs.setBooleanValue(IncludeVectorKey, it) } }
                    MaterialYouSwitch(useMaterialYou) { scope.launch { prefs.setBooleanValue(
                        MonochromeKey, it) } }
                }

                ThemedIconsSwitch(useThemed) { scope.launch { prefs.setBooleanValue(ExportThemedKey, it) } }
    }
}

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
