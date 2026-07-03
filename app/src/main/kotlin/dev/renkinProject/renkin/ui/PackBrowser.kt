@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.extension.calendarPrefixOrNull
import dev.renkinProject.renkin.extension.isCalendarDayName
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.icon.creator.PackIconPreview

@Composable
fun PackSectionHeader(iconPack: IconPack, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PackIconImage(iconPack.packageName, 24.dp)
        Text(
            text = iconPack.applicationName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            // Tapping anywhere on the row opens the full grid for just this pack
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// Subtle green frame on the icon the user picked from this pack, so the selection is
// visible on the grid itself (not just in the header). Shares the added-green token.
private val selectedIconBorderColor = AddedGreen

@Composable
private fun Modifier.selectedIconBorder(selected: Boolean): Modifier {
    // Animate the width so the frame eases in/out instead of snapping.
    val width by animateDpAsState(if (selected) 2.dp else 0.dp, label = "selectedIconBorder")
    return if (width > 0.dp) border(width, selectedIconBorderColor, RoundedCornerShape(16.dp)) else this
}

@Composable
fun PackIconsRow(
    iconPack: IconPack,
    options: GenerationOptions,
    sortOrder: IconSortOrder,
    query: String = "",
    selectedResourceId: Int? = null,
    // Prefix of the calendar icon picked from this pack, so its day-siblings are framed too.
    selectedCalendarPrefix: String? = null,
    onMore: (() -> Unit)? = null,
    onResult: (hasMatches: Boolean) -> Unit = {},
    onSelect: (ResourceDrawable, IconPackDrawable, String) -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    var iconPairs by remember { mutableStateOf<List<PackIconPreview>>(emptyList()) }
    var moreCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    // The picked pack (primaryIconPack) doesn't change how a pack's preview icons render — the
    // generator colourises by explicit pack name — so exclude it from the key. Otherwise picking
    // an icon (which sets primaryIconPack) re-keys every visible row and flashes the loader.
    val previewOptions = remember(options) { options.copy(primaryIconPack = "") }

    LaunchedEffect(iconPack.packageName, sortOrder, query, previewOptions) {
        isLoading = true
        // The view model serves a cached result instantly (no suspension) when it has one, so
        // a row scrolled back into view shows immediately without a loading flash.
        val result = viewModel.packRowPreviews(iconPack.packageName, sortOrder, query, previewOptions)
        iconPairs = result.previews
        moreCount = result.moreCount
        isLoading = false
        onResult(result.previews.isNotEmpty())
    }

    val calendarPrefixes = rememberCalendarPrefixes(iconPack, iconPairs)

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    } else if (iconPairs.isEmpty()) {
        // Thin inline slot for a single pack — no icon, just the message.
        EmptyState(
            text = stringResource(R.string.noIconsFound),
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(iconPairs, key = { _, item -> item.resource.resourceId }) { _, item ->
                PackIconItem(
                    item = item,
                    selected = item.resource.resourceId == selectedResourceId,
                    isCalendarGroup = item.drawableName.calendarPrefixOrNull() in calendarPrefixes,
                    inSelectedCalendarGroup = item.drawableName.inCalendarGroup(selectedCalendarPrefix),
                    onSelect = { onSelect(item.resource, item.drawable, item.drawableName) }
                )
            }
            if (moreCount > 0 && onMore != null) {
                item(key = "more") {
                    Box(modifier = Modifier.size(64.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = onMore,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "+$moreCount",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PackDetailGrid(
    iconPack: IconPack,
    options: GenerationOptions,
    sortOrder: IconSortOrder,
    query: String,
    selectedResourceId: Int? = null,
    // Prefix of the calendar icon picked from this pack, so its day-siblings are framed too.
    selectedCalendarPrefix: String? = null,
    onBack: () -> Unit,
    // Hoisted by the dialog so it can fade the comparison labels by the grid's distance from top.
    gridState: LazyGridState,
    onSelect: (ResourceDrawable, IconPackDrawable, String) -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    var iconPairs by remember { mutableStateOf<List<PackIconPreview>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(iconPack.packageName, sortOrder, query) {
        isLoading = true
        iconPairs = emptyList()
        // The grid fills progressively as the view model streams chunks back on the main thread.
        viewModel.packDetailPreviews(iconPack.packageName, sortOrder, query, options) { chunk ->
            iconPairs = (iconPairs + chunk).distinctBy { it.resource.resourceId }
        }
        isLoading = false
    }

    val calendarPrefixes = rememberCalendarPrefixes(iconPack, iconPairs)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
            }
            PackIconImage(iconPack.packageName, 28.dp)
            Text(
                text = iconPack.applicationName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!isLoading) {
                Text(
                    text = iconPairs.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (isLoading) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        if (!isLoading && iconPairs.isEmpty()) {
            // Full grid area is empty — show the icon + message like the home/watch empty states.
            EmptyState(
                icon = Icons.Filled.SearchOff,
                text = stringResource(R.string.noIconsFound),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)
            ) {
                items(iconPairs, key = { it.resource.resourceId }) { item ->
                    PackIconItem(
                        item = item,
                        selected = item.resource.resourceId == selectedResourceId,
                        isCalendarGroup = item.drawableName.calendarPrefixOrNull() in calendarPrefixes,
                        inSelectedCalendarGroup = item.drawableName.inCalendarGroup(selectedCalendarPrefix),
                        modifier = Modifier.animateItem(),
                        size = 56.dp,
                        onSelect = { onSelect(item.resource, item.drawable, item.drawableName) }
                    )
                }
            }
        }
    }
}

/**
 * True when this drawable name is a day in the selected calendar's rotation set, i.e. it shares
 * [prefix] and ends in 1–2 digits (e.g. prefix "bee_calendar_" matches "bee_calendar_26").
 * Null/blank prefix means no calendar icon is selected, so nothing is grouped.
 */
private fun String.inCalendarGroup(prefix: String?): Boolean =
    !prefix.isNullOrEmpty() && startsWith(prefix) && isCalendarDayName()

/**
 * The prefixes among [iconPairs] that are genuine calendar rotation sets in [iconPack] (a full
 * month exists, or the pack declares them), so only real calendars get badged — not anything
 * whose name ends in a number. Validation runs off the main thread once per loaded set; badging
 * is then a cheap membership test. Shared by the row preview and the detail grid.
 */
@Composable
private fun rememberCalendarPrefixes(iconPack: IconPack, iconPairs: List<PackIconPreview>): Set<String> {
    val viewModel: MainViewModel = hiltViewModel()
    var prefixes by remember(iconPack.packageName) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(iconPack.packageName, iconPairs) {
        val candidates = iconPairs.mapNotNull { it.drawableName.calendarPrefixOrNull() }.distinct()
        prefixes = viewModel.calendarPrefixesAmong(iconPack.packageName, candidates)
    }
    return prefixes
}

/**
 * A single icon slot used in both the row preview and the detail grid.
 * Shows a subtle calendar badge (DateRange icon) when [isCalendarGroup] is true,
 * so the user can recognise which icons belong to the calendar day-rotation set.
 */
@Composable
private fun PackIconItem(
    item: PackIconPreview,
    selected: Boolean,
    isCalendarGroup: Boolean,
    modifier: Modifier = Modifier,
    // True when this icon is a day-sibling (same prefix) of the calendar icon the user picked,
    // so the whole rotation set is framed in the selection colour, not just the picked day.
    inSelectedCalendarGroup: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    onSelect: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .padding(4.dp)
    ) {
        Image(
            bitmap = item.preview,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .selectedIconBorder(selected || inSelectedCalendarGroup)
                .let { m ->
                    if (isCalendarGroup && !selected && !inSelectedCalendarGroup) m.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
                        RoundedCornerShape(16.dp)
                    ) else m
                }
                .tappableIcon(onSelect)
        )
        if (isCalendarGroup) {
            BadgeTooltip(stringResource(R.string.calendarGroupTooltip), Modifier.align(Alignment.TopEnd)) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
    }
}
