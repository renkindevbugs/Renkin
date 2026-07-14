package dev.renkinProject.renkin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.ui.theme.CardShape
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
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_DEFAULT
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.OutlineColorKey
import dev.renkinProject.renkin.data.OutlineWidthKey
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
import dev.renkinProject.renkin.data.getColorValue
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.setIntValue
import kotlin.math.roundToInt
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setColorValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.drawable.IconPackDrawable
import kotlinx.coroutines.launch

@Composable
fun OptionsCard(
    iconPacks: List<IconPack>
) {
    val prefs = getPreferences()

    var expanded by remember { mutableStateOf(false) }

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
    var outlineAdd by rememberSaveable { mutableStateOf(false) }
    var outlineWidth by rememberSaveable { mutableStateOf(OUTLINE_WIDTH_DEFAULT) }

    val currentColor = prefs.getIconColor()
    val currentBgColor = prefs.getBackgroundColor()
    val currentOutlineColor = prefs.getColorValue(OutlineColorKey, androidx.compose.ui.graphics.Color.Black)

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
    outlineAdd = prefs.getBooleanValue(OutlineAddKey)
    outlineWidth = prefs.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)

    val pathTracing = isPathTracingEnabled(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit)
    val showIconColor = showIconColor(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit, useThemed)
    val showBgColor = showBackgroundColor(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit, useThemed)

    val scope = rememberCoroutineScope()

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
    ) {
        // No inner scroll — the card lives inside the app list and scrolls with it
        Column {
            Row(modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.advancedOptions),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                val chevronRotation by animateFloatAsState(
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

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                // Users otherwise don't know these settings only take effect after a refresh
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

                // The primary source/pack itself is picked in the hero card on the home screen;
                // only its tweaks (image modifier, text type) live here.
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

                if (showIconColor) {
                    ColorButton(stringResource(R.string.iconColor), currentColor) { scope.launch { prefs.setColorValue(
                        IconColorKey, it) } }
                }
                if (showBgColor) {
                    ColorButton(stringResource(R.string.backgroundColor), currentBgColor) { scope.launch { prefs.setColorValue(
                        BackgroundColorKey, it) } }
                }

                OverrideIconSwitch(overrideIcon) { scope.launch { prefs.setBooleanValue(
                    OverrideIconKey, it) } }

                if (pathTracing) {
                    VectorSwitch(useVector) { scope.launch { prefs.setBooleanValue(IncludeVectorKey, it) } }
                    MaterialYouSwitch(useMaterialYou) { scope.launch { prefs.setBooleanValue(
                        MonochromeKey, it) } }
                }

                ThemedIconsSwitch(useThemed) { scope.launch { prefs.setBooleanValue(ExportThemedKey, it) } }

                // Pack-wide outline (Add only): a contour around every generated icon.
                // Recolor needs the individual icon's artwork, so it stays per app.
                OutlineAddSwitch(outlineAdd) { scope.launch { prefs.setBooleanValue(OutlineAddKey, it) } }
                AnimatedVisibility(visible = outlineAdd) {
                    Column {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            // Local echo while dragging; DataStore only sees the released value.
                            var dragWidth by remember { mutableStateOf<Float?>(null) }
                            val shownWidth = dragWidth ?: outlineWidth.toFloat()
                            LabeledSlider(
                                label = stringResource(R.string.outlineThickness),
                                value = shownWidth,
                                onValueChange = { dragWidth = it },
                                valueRange = 1f..16f,
                                valueLabel = "${shownWidth.roundToInt()} px",
                                onValueChangeFinished = {
                                    val released = dragWidth?.roundToInt()
                                    dragWidth = null
                                    if (released != null) scope.launch { prefs.setIntValue(OutlineWidthKey, released) }
                                }
                            )
                        }
                        ColorButton(stringResource(R.string.outlineColor), currentOutlineColor) { scope.launch { prefs.setColorValue(
                            OutlineColorKey, it) } }
                    }
                }
                }
            }
        }
    }
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
