@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.alembiconsProject.alembicons.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.icon.creator.IconSortOrder
import kotlinx.coroutines.delay

/**
 * The icon search field plus the A→Z / Z→A sort menu. Rendered as the first item of the
 * pack list so it scrolls with the content (see CreateTab) rather than animating in and out.
 */
@Composable
private fun IconSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortOrder: IconSortOrder,
    onSortOrderChange: (IconSortOrder) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.searchIcons),
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort), tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                CheckableDropdownItem(stringResource(R.string.sortAToZ), sortOrder == IconSortOrder.NAME_ASC) {
                    onSortOrderChange(IconSortOrder.NAME_ASC); showSortMenu = false
                }
                CheckableDropdownItem(stringResource(R.string.sortZToA), sortOrder == IconSortOrder.NAME_DESC) {
                    onSortOrderChange(IconSortOrder.NAME_DESC); showSortMenu = false
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun CreateTab(
    source: Source,
    iconPacks: List<IconPack>,
    options: GenerationOptions,
    textType: TextType,
    // Hoisted by the dialog so the typed (or cleared) query survives leaving and returning to
    // this tab; it's seeded with the app name and reset per edit at the dialog level.
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    // drawableName is the raw resource name (e.g. "bee_calendar_6") — used to derive the
    // calendar prefix when the user opts into day rotation.
    onIconSelect: (ResourceDrawable, IconPack, String) -> Unit,
    onTextTypeChange: (TextType) -> Unit,
    onCollapsedChange: (Boolean) -> Unit = {},
    contentReady: Boolean = true,
    // Resource id of the icon currently picked (from options.primaryIconPack), so the grid
    // can frame it. null = nothing picked yet.
    selectedResourceId: Int? = null,
    // Prefix of the picked calendar icon (e.g. "bee_calendar_"), so the grid frames the whole
    // day-rotation set alongside the picked icon. null = no calendar icon selected.
    selectedCalendarPrefix: String? = null,
    // Whether this app ships a Material You <monochrome> layer, so the Application Icon source
    // can offer the recolourable Monochrome variant (and disable it otherwise).
    appHasMonochrome: Boolean = false,
    // Switches the Application Icon variant between the full-colour icon (false) and the
    // recoloured monochrome layer (true). Mirrors options.monochrome.
    onMonochromeChange: (Boolean) -> Unit = {}
) {
    // Seeded from the hoisted query so returning to the tab doesn't trigger a spurious
    // re-search (debouncedQuery already matches the preserved searchQuery).
    var debouncedQuery by remember { mutableStateOf(searchQuery) }
    var sortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var expandedPack by remember { mutableStateOf<IconPack?>(null) }
    var collapsed by remember { mutableStateOf(false) }
    val distinctPacks = remember(iconPacks) { iconPacks.distinctBy { it.packageName } }
    // packageName -> whether the pack has icons matching the current query
    var packMatches by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // The order actually shown. Updated only once the results settle so the list
    // doesn't shuffle on every pack that finishes loading.
    var orderedPacks by remember(iconPacks) { mutableStateOf(distinctPacks) }

    val listState = rememberLazyListState()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery != debouncedQuery) {
            delay(300)
            debouncedQuery = searchQuery
        }
    }

    // Forget previous results and restore the default order when the query changes
    LaunchedEffect(debouncedQuery) {
        packMatches = emptyMap()
        orderedPacks = distinctPacks
    }

    // Reorder once the match results settle (not on every pack) and pin the user
    // to the top so packs floating up don't push them down
    LaunchedEffect(packMatches) {
        if (packMatches.isEmpty()) return@LaunchedEffect
        delay(450)
        val newOrder = distinctPacks.sortedByDescending { packMatches[it.packageName] != false }
        if (newOrder.map { it.packageName } != orderedPacks.map { it.packageName }) {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            orderedPacks = newOrder
            if (atTop) listState.scrollToItem(0)
        }
    }

    LaunchedEffect(listScrolled, expandedPack) {
        if (expandedPack == null) collapsed = listScrolled
    }

    LaunchedEffect(collapsed, source) {
        onCollapsedChange(collapsed && source == Source.ICON_PACK)
    }

    BackHandler(enabled = expandedPack != null) {
        expandedPack = null
    }

    Column(Modifier.fillMaxSize()) {
        when (source) {
            Source.ICON_PACK -> {
                val detailPack = expandedPack
                if (detailPack != null) {
                    PackDetailGrid(
                        iconPack = detailPack,
                        options = options,
                        sortOrder = sortOrder,
                        query = debouncedQuery,
                        selectedResourceId = selectedResourceId.takeIf { detailPack.packageName == options.primaryIconPack },
                        selectedCalendarPrefix = selectedCalendarPrefix?.takeIf { detailPack.packageName == options.primaryIconPack },
                        onBack = { expandedPack = null },
                        onCollapsedChange = { collapsed = it },
                        onSelect = { resource, _, drawableName -> onIconSelect(resource, detailPack, drawableName) }
                    )
                } else if (!contentReady) {
                    // Hold off mounting the (heavy) pack browser until the dialog has
                    // finished opening, so the open animation stays smooth
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        // The search bar is the first list item, so it scrolls away 1:1 with
                        // the icons (perfectly smooth, no animation) and comes back only when
                        // the list is scrolled all the way to the top.
                        item(key = "search") {
                            IconSearchBar(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                sortOrder = sortOrder,
                                onSortOrderChange = { sortOrder = it }
                            )
                        }
                        orderedPacks.forEach { pack ->
                            item(key = "${pack.packageName}_header") {
                                Box(Modifier.animateItem()) {
                                    PackSectionHeader(pack) { expandedPack = pack }
                                }
                            }
                            item(key = "${pack.packageName}_icons") {
                                Box(Modifier.animateItem()) {
                                    PackIconsRow(
                                        iconPack = pack,
                                        options = options,
                                        sortOrder = sortOrder,
                                        query = debouncedQuery,
                                        selectedResourceId = selectedResourceId.takeIf { pack.packageName == options.primaryIconPack },
                                        selectedCalendarPrefix = selectedCalendarPrefix?.takeIf { pack.packageName == options.primaryIconPack },
                                        onMore = { expandedPack = pack },
                                        onResult = { hasMatches ->
                                            packMatches = packMatches + (pack.packageName to hasMatches)
                                        }
                                    ) { resource, _, drawableName ->
                                        onIconSelect(resource, pack, drawableName)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Source.APPLICATION_ICON -> ApplicationIconVariant(
                monochrome = options.monochrome,
                appHasMonochrome = appHasMonochrome,
                onMonochromeChange = onMonochromeChange
            )
            Source.APPLICATION_NAME -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                TextTypeDropdown(R.string.textType, textType) { onTextTypeChange(it) }
            }
            else -> {}
        }
    }
}

/**
 * Application Icon source options: pick between the app's full-colour icon and its recoloured
 * Material You monochrome layer. The Monochrome choice is disabled when the app ships no
 * `<monochrome>` layer; its colours are edited in the Modifier tab.
 */
@Composable
private fun ApplicationIconVariant(
    monochrome: Boolean,
    appHasMonochrome: Boolean,
    onMonochromeChange: (Boolean) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.iconVariant),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        ) {
            VariantSegment(
                label = stringResource(R.string.variantDefault),
                selected = !monochrome,
                enabled = true,
                modifier = Modifier.weight(1f)
            ) { onMonochromeChange(false) }
            VariantSegment(
                label = stringResource(R.string.variantMonochrome),
                selected = monochrome,
                enabled = appHasMonochrome,
                modifier = Modifier.weight(1f)
            ) { onMonochromeChange(true) }
        }
        val hint = when {
            !appHasMonochrome -> stringResource(R.string.monochromeUnavailable)
            monochrome -> stringResource(R.string.monochromeRecolorHint)
            else -> stringResource(R.string.variantDefaultHint)
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun VariantSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}
