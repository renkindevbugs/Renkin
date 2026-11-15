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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.extension.calendarPrefixOrNull
import dev.renkinProject.renkin.extension.isCalendarDayName
import dev.renkinProject.renkin.extension.prettyDrawableName
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.PACK_DETAIL_LIMIT
import dev.renkinProject.renkin.icon.creator.PACK_ROW_LIMIT
import dev.renkinProject.renkin.icon.creator.PackSearchResult
import dev.renkinProject.renkin.icon.creator.withSource
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.util.runCatchingCancellable
import kotlinx.coroutines.awaitCancellation

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
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private val selectedIconBorderColor = AddedGreen

@Composable
private fun Modifier.selectedIconBorder(selected: Boolean): Modifier {
    val width by animateDpAsState(if (selected) 2.dp else 0.dp, label = "selectedIconBorder")
    return if (width > 0.dp) border(width, selectedIconBorderColor, FieldShape) else this
}

@Composable
fun PackIconsRow(
    iconPack: IconPack,
    options: GenerationOptions,
    result: PackSearchResult,
    selectedResourceId: Int? = null,
    selectedCalendarPrefix: String? = null,
    onMore: (() -> Unit)? = null,
    onLoadingChange: (Boolean) -> Unit = {},
    onSelect: (String) -> Unit
) {
    val names = remember(result.names) { result.names.take(PACK_ROW_LIMIT) }
    val previewOptions = remember(options) { options.withSource { copy(primaryIconPack = "") } }
    val calendarPrefixes = rememberCalendarPrefixes(iconPack, result.identity, names)
    val clockDrawables = rememberDynamicClockDrawables(iconPack, result.identity)
    ReportPackSearchLoading(result, onLoadingChange)

    Column {
        if (names.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(names, key = { it }) { name ->
                    PackIconItem(
                        iconPack = iconPack,
                        identity = result.identity,
                        drawableName = name,
                        options = previewOptions,
                        selectedResourceId = selectedResourceId,
                        isCalendarGroup = name.calendarPrefixOrNull() in calendarPrefixes,
                        inSelectedCalendarGroup = name.inCalendarGroup(selectedCalendarPrefix),
                        isDynamicClock = name in clockDrawables,
                        onLoadingChange = onLoadingChange,
                        onSelect = { onSelect(name) }
                    )
                }
                val moreCount = (result.names.size - PACK_ROW_LIMIT).coerceAtLeast(0)
                if (moreCount > 0 && onMore != null) {
                    item(key = "pack-more:${iconPack.packageName}") {
                        Box(Modifier.size(64.dp).padding(4.dp), contentAlignment = Alignment.Center) {
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
        when {
            result.failed -> Text(
                text = stringResource(R.string.packSearchFailed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            !result.complete -> Box(
                modifier = Modifier.fillMaxWidth().let { if (names.isEmpty()) it.height(80.dp) else it },
                contentAlignment = Alignment.Center
            ) {
                WavyLoadingBar(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            names.isEmpty() -> EmptyState(
                text = stringResource(R.string.noIconsFound),
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )
        }
    }
}

@Composable
fun PackDetailGrid(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    iconPack: IconPack,
    options: GenerationOptions,
    result: PackSearchResult,
    selectedResourceId: Int? = null,
    selectedCalendarPrefix: String? = null,
    onBack: () -> Unit,
    gridState: LazyGridState,
    onLoadingChange: (Boolean) -> Unit = {},
    onSelect: (String) -> Unit
) {
    val names = remember(result.names) { result.names.take(PACK_DETAIL_LIMIT) }
    val previewOptions = remember(options) { options.withSource { copy(primaryIconPack = "") } }
    val calendarPrefixes = rememberCalendarPrefixes(iconPack, result.identity, names)
    val clockDrawables = rememberDynamicClockDrawables(iconPack, result.identity)
    ReportPackSearchLoading(result, onLoadingChange)

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
            }
            PackIconImage(iconPack.packageName, 28.dp)
            Text(
                text = iconPack.applicationName,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (result.complete && !result.failed) {
                Text(
                    text = names.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (result.failed) {
            Text(
                text = stringResource(R.string.packSearchFailed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else if (!result.complete) {
            WavyLoadingBar(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (result.complete && !result.failed && names.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                text = stringResource(R.string.noIconsFound),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().drawVerticalScrollbar(gridState, spanCount = 4),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)
            ) {
                items(names, key = { it }) { name ->
                    PackIconItem(
                        iconPack = iconPack,
                        identity = result.identity,
                        drawableName = name,
                        options = previewOptions,
                        selectedResourceId = selectedResourceId,
                        isCalendarGroup = name.calendarPrefixOrNull() in calendarPrefixes,
                        inSelectedCalendarGroup = name.inCalendarGroup(selectedCalendarPrefix),
                        isDynamicClock = name in clockDrawables,
                        size = 56.dp,
                        modifier = Modifier.animateItem(),
                        onLoadingChange = onLoadingChange,
                        onSelect = { onSelect(name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportPackSearchLoading(result: PackSearchResult, onLoadingChange: (Boolean) -> Unit) {
    LaunchedEffect(result.identity, result.complete, result.failed) {
        if (!result.complete && !result.failed) {
            onLoadingChange(true)
            try {
                awaitCancellation()
            } finally {
                onLoadingChange(false)
            }
        }
    }
}

private fun String.inCalendarGroup(prefix: String?): Boolean =
    !prefix.isNullOrEmpty() && startsWith(prefix) && isCalendarDayName()

@Composable
private fun rememberCalendarPrefixes(iconPack: IconPack, identity: String, names: List<String>): Set<String> {
    val viewModel = dev.renkinProject.renkin.feature.buildinstall.iconEditorViewModel()
    var prefixes by remember(iconPack, identity) { mutableStateOf(emptySet<String>()) }
    val candidates = remember(names) { names.mapNotNull { it.calendarPrefixOrNull() }.distinct() }
    LaunchedEffect(iconPack, identity, candidates) {
        prefixes = viewModel.calendarPrefixesAmong(iconPack.packageName, candidates)
    }
    return prefixes
}

@Composable
private fun rememberDynamicClockDrawables(iconPack: IconPack, identity: String): Set<String> {
    val viewModel = dev.renkinProject.renkin.feature.buildinstall.iconEditorViewModel()
    var drawables by remember(iconPack, identity) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(iconPack, identity) {
        drawables = viewModel.dynamicClockDrawables(iconPack.packageName)
    }
    return drawables
}

@Composable
private fun PackIconItem(
    iconPack: IconPack,
    identity: String,
    drawableName: String,
    options: GenerationOptions,
    selectedResourceId: Int?,
    isCalendarGroup: Boolean,
    modifier: Modifier = Modifier,
    inSelectedCalendarGroup: Boolean = false,
    isDynamicClock: Boolean = false,
    size: Dp = 64.dp,
    onLoadingChange: (Boolean) -> Unit,
    onSelect: () -> Unit
) {
    val viewModel = dev.renkinProject.renkin.feature.buildinstall.iconEditorViewModel()
    var thumbnail by remember(iconPack, identity, drawableName, options) {
        mutableStateOf(viewModel.cachedPackThumbnail(iconPack, identity, drawableName, options, sizePx = 96))
    }
    var loading by remember(iconPack, identity, drawableName, options) { mutableStateOf(thumbnail == null) }
    LaunchedEffect(iconPack, identity, drawableName, options) {
        if (thumbnail == null) {
            onLoadingChange(true)
            try {
                thumbnail = runCatchingCancellable {
                    viewModel.loadPackThumbnail(iconPack, identity, drawableName, options, sizePx = 96)
                }.getOrNull()
                loading = false
            } finally {
                onLoadingChange(false)
            }
        }
    }
    val iconName = remember(drawableName) { drawableName.prettyDrawableName() }
    val unavailable = stringResource(R.string.packIconUnavailable)
    val ready = thumbnail
    val selected = ready != null && ready.resourceId == selectedResourceId
    val badged = isCalendarGroup || isDynamicClock

    Box(modifier = modifier.size(size).padding(4.dp)) {
        RenkinTooltipBox(if (!loading && ready == null) "$iconName: $unavailable" else iconName) {
            if (ready != null) {
                Image(
                    bitmap = ready.preview,
                    contentDescription = iconName,
                    modifier = Modifier.fillMaxSize().clip(FieldShape)
                        .selectedIconBorder(selected || inSelectedCalendarGroup)
                        .let { tile ->
                            if (badged && !selected && !inSelectedCalendarGroup) tile.border(
                                1.5.dp,
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
                                FieldShape
                            ) else tile
                        }
                        .tappableIcon(onSelect)
                )
            } else {
                Box(
                    Modifier.fillMaxSize().clip(FieldShape).background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        WavyLoadingBar(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    } else {
                        Text(
                            text = unavailable,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
            }
        }
        if (badged) {
            BadgeTooltip(
                stringResource(if (isCalendarGroup) R.string.calendarGroupTooltip else R.string.dynamicClockTooltip),
                Modifier.align(Alignment.TopEnd)
            ) {
                RotatingIconBadge(if (isCalendarGroup) RotatingIconKind.CALENDAR else RotatingIconKind.CLOCK)
            }
        }
    }
}
