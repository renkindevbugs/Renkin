@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import kotlinx.coroutines.delay

/** How the pack list itself is ordered (the icons inside sort by [IconSortOrder]). */
enum class PackSortOrder { USAGE, NAME, INSTALL_DATE }

/** Muted section label inside the sort menu, separating the icon sort from the pack sort. */
@Composable
private fun SortMenuHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/**
 * The icon search field plus the sort menu: icon order (A→Z / Z→A) and pack order
 * (most used / name / recently installed). Pinned above the pack list (see CreateTab).
 */
@Composable
private fun IconSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortOrder: IconSortOrder,
    onSortOrderChange: (IconSortOrder) -> Unit,
    packSortOrder: PackSortOrder,
    onPackSortOrderChange: (PackSortOrder) -> Unit
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
                SortMenuHeader(stringResource(R.string.sortIconsHeader))
                CheckableDropdownItem(stringResource(R.string.sortAToZ), sortOrder == IconSortOrder.NAME_ASC) {
                    onSortOrderChange(IconSortOrder.NAME_ASC); showSortMenu = false
                }
                CheckableDropdownItem(stringResource(R.string.sortZToA), sortOrder == IconSortOrder.NAME_DESC) {
                    onSortOrderChange(IconSortOrder.NAME_DESC); showSortMenu = false
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SortMenuHeader(stringResource(R.string.sortPacksHeader))
                CheckableDropdownItem(stringResource(R.string.sortByUsage), packSortOrder == PackSortOrder.USAGE) {
                    onPackSortOrderChange(PackSortOrder.USAGE); showSortMenu = false
                }
                CheckableDropdownItem(stringResource(R.string.sortByName), packSortOrder == PackSortOrder.NAME) {
                    onPackSortOrderChange(PackSortOrder.NAME); showSortMenu = false
                }
                CheckableDropdownItem(stringResource(R.string.sortByInstallDate), packSortOrder == PackSortOrder.INSTALL_DATE) {
                    onPackSortOrderChange(PackSortOrder.INSTALL_DATE); showSortMenu = false
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
    // The pack list and single-pack grid scroll states + which pack is expanded, hoisted by the
    // dialog so it can fade the comparison labels by whichever list is showing.
    listState: LazyListState,
    gridState: LazyGridState,
    expandedPack: IconPack?,
    onExpandedPackChange: (IconPack?) -> Unit,
    // How much of the pinned search bar to show: 1 = full, 0 = collapsed. A lambda (read in the
    // layout/draw phase) so the enter-always collapse re-lays-out only the bar, not the whole list.
    searchBarExpand: () -> Float = { 1f },
    // How many stored icons came from each pack, keyed by package name. Packs the user takes
    // from most often sort higher (after packs that actually have an icon for the query).
    packUsage: Map<String, Int> = emptyMap(),
    // Hoisted by the dialog so the typed (or cleared) query survives leaving and returning to
    // this tab; it's seeded with the app name and reset per edit at the dialog level.
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    // drawableName is the raw resource name (e.g. "bee_calendar_6") — used to derive the
    // calendar prefix when the user opts into day rotation.
    onIconSelect: (ResourceDrawable, IconPack, String) -> Unit,
    onTextTypeChange: (TextType) -> Unit,
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
    onMonochromeChange: (Boolean) -> Unit = {},
    // Wallpaper-derived colour schemes (foreground, background) offered for the monochrome variant.
    monochromeSchemes: List<Pair<Color, Color>> = emptyList(),
    // Index of the chosen scheme; == monochromeSchemes.size means the Custom (manual colour) option.
    selectedScheme: Int = 0,
    onSchemeChange: (Int) -> Unit = {},
    // Custom-scheme foreground/background, edited inline when the Custom swatch is selected.
    customForeground: Color = Color.White,
    customBackground: Color = Color.Black,
    onCustomForegroundChange: (Color) -> Unit = {},
    onCustomBackgroundChange: (Color) -> Unit = {}
) {
    // Seeded from the hoisted query so returning to the tab doesn't trigger a spurious
    // re-search (debouncedQuery already matches the preserved searchQuery).
    var debouncedQuery by remember { mutableStateOf(searchQuery) }
    var sortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var packSort by rememberSaveable { mutableStateOf(PackSortOrder.USAGE) }
    // Pack first-install times, fetched only once the recently-installed sort is chosen.
    val viewModel: MainViewModel = hiltViewModel()
    val packInstallTimes by produceState(emptyMap<String, Long>(), packSort, iconPacks) {
        if (packSort == PackSortOrder.INSTALL_DATE && value.isEmpty()) {
            value = viewModel.installTimes(iconPacks.map { it.packageName })
        }
    }
    // Base order per the chosen pack sort; most-used is the default (usage stays the tiebreaker
    // once query matches settle below).
    val distinctPacks = remember(iconPacks, packUsage, packSort, packInstallTimes) {
        val base = iconPacks.distinctBy { it.packageName }
        when (packSort) {
            PackSortOrder.USAGE -> base.sortedByDescending { packUsage[it.packageName] ?: 0 }
            PackSortOrder.NAME -> base.sortedBy { it.applicationName.lowercase() }
            PackSortOrder.INSTALL_DATE -> base.sortedByDescending { packInstallTimes[it.packageName] ?: 0L }
        }
    }
    // packageName -> whether the pack has icons matching the current query
    var packMatches by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // The order actually shown. Updated only once the results settle so the list
    // doesn't shuffle on every pack that finishes loading.
    var orderedPacks by remember(distinctPacks) { mutableStateOf(distinctPacks) }

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
        // Packs that have a matching icon float to the top; among equals, the chosen pack sort
        // (distinctPacks' order) is the stable tiebreaker.
        val baseIndex = distinctPacks.withIndex().associate { it.value.packageName to it.index }
        val newOrder = distinctPacks.sortedWith(
            compareByDescending<IconPack> { packMatches[it.packageName] != false }
                .thenBy { baseIndex[it.packageName] ?: Int.MAX_VALUE }
        )
        if (newOrder.map { it.packageName } != orderedPacks.map { it.packageName }) {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            orderedPacks = newOrder
            if (atTop) listState.scrollToItem(0)
        }
    }

    BackHandler(enabled = expandedPack != null) {
        onExpandedPackChange(null)
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
                        gridState = gridState,
                        onBack = { onExpandedPackChange(null) },
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
                    Column(Modifier.fillMaxSize()) {
                        // Pinned above the list (not a list item): reordering or scrolling the pack
                        // list must not touch the focused search field. As a list item it shared the
                        // LazyColumn's relayout, and a reshuffle/scrollToItem mid-typing desynced the
                        // IME — dropped key presses and a cursor that jumped to the start. It still
                        // collapses pixel-by-pixel with the scroll (enter-always) via collapsibleHeight.
                        Box(Modifier.collapsibleHeight(searchBarExpand)) {
                            IconSearchBar(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                sortOrder = sortOrder,
                                onSortOrderChange = { sortOrder = it },
                                packSortOrder = packSort,
                                onPackSortOrderChange = { packSort = it }
                            )
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            orderedPacks.forEach { pack ->
                                item(key = "${pack.packageName}_header") {
                                    Box(Modifier.animateItem()) {
                                        PackSectionHeader(pack) { onExpandedPackChange(pack) }
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
                                            onMore = { onExpandedPackChange(pack) },
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
            }
            Source.APPLICATION_ICON -> ApplicationIconVariant(
                monochrome = options.monochrome,
                appHasMonochrome = appHasMonochrome,
                onMonochromeChange = onMonochromeChange,
                schemes = monochromeSchemes,
                selectedScheme = selectedScheme,
                onSchemeChange = onSchemeChange,
                customForeground = customForeground,
                customBackground = customBackground,
                onCustomForegroundChange = onCustomForegroundChange,
                onCustomBackgroundChange = onCustomBackgroundChange
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
    onMonochromeChange: (Boolean) -> Unit,
    schemes: List<Pair<Color, Color>>,
    selectedScheme: Int,
    onSchemeChange: (Int) -> Unit,
    customForeground: Color,
    customBackground: Color,
    onCustomForegroundChange: (Color) -> Unit,
    onCustomBackgroundChange: (Color) -> Unit
) {
    var fgPickerOpen by remember { mutableStateOf(false) }
    var bgPickerOpen by remember { mutableStateOf(false) }
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
        SegmentedRow {
            SegmentCell(
                label = stringResource(R.string.variantDefault),
                selected = !monochrome,
                modifier = Modifier.weight(1f)
            ) { onMonochromeChange(false) }
            SegmentCell(
                label = stringResource(R.string.variantMonochrome),
                selected = monochrome,
                enabled = appHasMonochrome,
                disabledHint = stringResource(R.string.monochromeUnavailable),
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

        if (monochrome && appHasMonochrome) {
            Text(
                text = stringResource(R.string.monochromeColors),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                schemes.forEachIndexed { index, (fg, bg) ->
                    SchemeSwatch(
                        foreground = fg,
                        background = bg,
                        custom = false,
                        selected = selectedScheme == index
                    ) { onSchemeChange(index) }
                }
                // Custom: foreground/background chosen with the inline pickers below.
                SchemeSwatch(
                    foreground = MaterialTheme.colorScheme.onSurface,
                    background = MaterialTheme.colorScheme.surfaceVariant,
                    custom = true,
                    selected = selectedScheme >= schemes.size
                ) { onSchemeChange(schemes.size) }
            }

            if (selectedScheme >= schemes.size) {
                Spacer(Modifier.height(12.dp))
                ColorRow(stringResource(R.string.iconColor), customForeground) { fgPickerOpen = true }
                Spacer(Modifier.height(8.dp))
                ColorRow(stringResource(R.string.backgroundColor), customBackground) { bgPickerOpen = true }
            } else {
                Text(
                    text = stringResource(R.string.monochromeColorsHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }

    if (fgPickerOpen) {
        ColorDialog(
            onDismiss = { fgPickerOpen = false },
            currentlySelected = customForeground,
            onColorSelected = onCustomForegroundChange
        )
    }
    if (bgPickerOpen) {
        ColorDialog(
            onDismiss = { bgPickerOpen = false },
            currentlySelected = customBackground,
            onColorSelected = onCustomBackgroundChange
        )
    }
}

/** A tappable colour row: label on the left, a circular swatch of [color] on the right. */
@Composable
private fun ColorRow(label: String, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
    }
}

/** A colour-scheme chip: the background tile with a foreground dot, or a palette glyph for Custom. */
@Composable
private fun SchemeSwatch(
    foreground: Color,
    background: Color,
    custom: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(if (selected) 2.dp else 0.5.dp, ring, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (custom) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.custom),
                tint = foreground,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(foreground)
            )
        }
    }
}

