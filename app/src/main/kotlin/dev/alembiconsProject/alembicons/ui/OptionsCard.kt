package dev.alembiconsProject.alembicons.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import dev.alembiconsProject.alembicons.ui.theme.AddedGreen
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.alembiconsProject.alembicons.MainViewModel
import dev.alembiconsProject.alembicons.ui.theme.CardShape
import dev.alembiconsProject.alembicons.ui.theme.FieldShape
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.BackgroundColorKey
import dev.alembiconsProject.alembicons.data.CalendarIconsKey
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IMAGE_EDIT_DEFAULT
import dev.alembiconsProject.alembicons.data.IconColorKey
import dev.alembiconsProject.alembicons.data.getIconColor
import dev.alembiconsProject.alembicons.data.getBackgroundColor
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.IncludeVectorKey
import dev.alembiconsProject.alembicons.data.MonochromeKey
import dev.alembiconsProject.alembicons.data.OverrideIconKey
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.PrimaryImageEditKey
import dev.alembiconsProject.alembicons.data.PrimarySourceKey
import dev.alembiconsProject.alembicons.data.PrimaryTextTypeKey
import dev.alembiconsProject.alembicons.data.SOURCE_DEFAULT
import dev.alembiconsProject.alembicons.data.SecondaryIconPackKey
import dev.alembiconsProject.alembicons.data.SecondaryImageEditKey
import dev.alembiconsProject.alembicons.data.SecondarySourceKey
import dev.alembiconsProject.alembicons.data.SecondaryTextTypeKey
import dev.alembiconsProject.alembicons.data.TEXT_TYPE_DEFAULT
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.data.setBooleanValue
import dev.alembiconsProject.alembicons.data.setColorValue
import dev.alembiconsProject.alembicons.data.setEnumValue
import dev.alembiconsProject.alembicons.data.setStringValue
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import kotlinx.coroutines.launch

@Composable
fun AppOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    themed: Boolean,
    onConfirm: (icon: IconPackDrawable?) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    OptionsDialog(iconPacks, app, themed, onConfirm, onDismiss, onIconClear)
}

// The green used for icons added since the last build (shares the added-green token).
private val addedColor = AddedGreen

/**
 * Segmented completion bar: blue = icons already in the last built pack, green = added
 * since (pending build), red = removed since. Material 3 has no multi-colour progress
 * bar, so this is a small custom one with the same rounded look.
 */
@Composable
private fun ChangeBar(total: Int, built: Int, added: Int, removed: Int) {
    val builtF by animateFloatAsState(if (total > 0) built / total.toFloat() else 0f, label = "builtFrac")
    val addedF by animateFloatAsState(if (total > 0) added / total.toFloat() else 0f, label = "addedFrac")
    val removedF by animateFloatAsState(if (total > 0) removed / total.toFloat() else 0f, label = "removedFrac")
    val rest = (1f - builtF - addedF - removedF).coerceAtLeast(0f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (builtF > 0f) Box(Modifier.fillMaxHeight().weight(builtF).background(MaterialTheme.colorScheme.primary))
        if (addedF > 0f) Box(Modifier.fillMaxHeight().weight(addedF).background(addedColor))
        if (removedF > 0f) Box(Modifier.fillMaxHeight().weight(removedF).background(MaterialTheme.colorScheme.error))
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

@Composable
fun OptionsCard(
    iconPacks: List<IconPack>
) {
    val prefs = getPreferences()

    // Completion progress across all apps (updates live as icons are assigned/cleared).
    // The bar is a diff against the last built pack: blue = already built, green = added
    // since (pending build), red = removed since. builtKeys updates after each build.
    val vm = hiltViewModel<MainViewModel>()
    val apps = vm.applicationList
    val builtKeys = vm.builtKeys
    val builtCount = apps.count { it.createdIcon != null && "${it.packageName}/${it.activityName}" in builtKeys }
    val addedCount = apps.count { it.createdIcon != null && "${it.packageName}/${it.activityName}" !in builtKeys }
    val removedCount = apps.count { it.createdIcon == null && "${it.packageName}/${it.activityName}" in builtKeys }
    val themedCount = builtCount + addedCount
    val totalCount = apps.size

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
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }
    var retrieveCalendarIcons by rememberSaveable { mutableStateOf(false) }
    var overrideIcon by rememberSaveable { mutableStateOf(false) }

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
    useMonochrome = prefs.getBooleanValue(MonochromeKey)
    useThemed = prefs.getBooleanValue(ExportThemedKey)
    retrieveCalendarIcons = prefs.getBooleanValue(CalendarIconsKey)
    overrideIcon = prefs.getBooleanValue(OverrideIconKey)

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
                    text = stringResource(id = R.string.options),
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

            // Always-visible completion progress, so the user sees how much is left
            if (totalCount > 0) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.completionProgress, themedCount, totalCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // Pending changes since the last build, mirroring the bar colours
                        if (addedCount > 0) {
                            Text(
                                text = "+$addedCount",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = addedColor
                            )
                        }
                        if (removedCount > 0) {
                            Text(
                                text = " −$removedCount",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    ChangeBar(totalCount, builtCount, addedCount, removedCount)
                }
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

                SourceDropdown(R.string.primarySource, primarySource) { scope.launch { prefs.setEnumValue(PrimarySourceKey, it) } }

                if (needIconPack(primarySource)) {
                    IconPackDropdown(R.string.primaryIconPack, iconPacks, primaryIconPack, null) { scope.launch { prefs.setStringValue(
                        PrimaryIconPackKey, it.packageName) } }
                }

                if (needImageEdit(primarySource)) {
                    ImageEditDropdown(R.string.primaryImageEdit, primaryImageEdit) { scope.launch { prefs.setEnumValue(
                        PrimaryImageEditKey, it) } }
                }

                if (needTextType(primarySource)) {
                    TextTypeDropdown(R.string.primaryTextType, primaryTextType) { scope.launch { prefs.setEnumValue(
                        PrimaryTextTypeKey, it) } }
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
                    MonochromeSwitch(useMonochrome) { scope.launch { prefs.setBooleanValue(
                        MonochromeKey, it) } }
                }

                ThemedIconsSwitch(useThemed) { scope.launch { prefs.setBooleanValue(ExportThemedKey, it) } }
                }
            }
        }
    }
}
