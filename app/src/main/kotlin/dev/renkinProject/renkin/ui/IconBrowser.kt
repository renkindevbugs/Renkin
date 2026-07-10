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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.MainViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import dev.renkinProject.renkin.icon.creator.TextCase
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
 * The sort menu button for the edit-mode top bar (Mihon-style): icon order (A→Z / Z→A)
 * and pack order (most used / name / recently installed) in one dropdown.
 */
@Composable
internal fun IconSortMenuButton(
    sortOrder: IconSortOrder,
    onSortOrderChange: (IconSortOrder) -> Unit,
    packSortOrder: PackSortOrder,
    onPackSortOrderChange: (PackSortOrder) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
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
            HorizontalDivider()
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

@Composable
fun CreateTab(
    source: Source,
    // Top inset of the overlaid header (Mihon-style scroll-under chrome): lazy lists apply it as
    // contentPadding so they can scroll beneath the header; static content pads normally.
    contentPadding: PaddingValues = PaddingValues(0.dp),
    iconPacks: List<IconPack>,
    options: GenerationOptions,
    textType: TextType,
    // The pack list and single-pack grid scroll states + which pack is expanded, hoisted by the
    // dialog so it can fade the comparison labels by whichever list is showing.
    listState: LazyListState,
    gridState: LazyGridState,
    expandedPack: IconPack?,
    onExpandedPackChange: (IconPack?) -> Unit,
    // Sort orders live in the dialog's top bar (Mihon-style), so they're hoisted with the query.
    sortOrder: IconSortOrder,
    packSort: PackSortOrder,
    // Reports whether icons are still loading / the search is still resolving, so the dialog's
    // top bar can show its activity line and hide it when the search has finished.
    onBusyChange: (Boolean) -> Unit = {},
    // How many stored icons came from each pack, keyed by package name. Packs the user takes
    // from most often sort higher (after packs that actually have an icon for the query).
    packUsage: Map<String, Int> = emptyMap(),
    // Hoisted by the dialog: the top-bar search field edits it there; it's seeded with the app
    // name and reset per edit at the dialog level.
    searchQuery: String,
    // The edited app's component while the query is still the default (untouched app name):
    // each pack then shows its appfilter-designated icon for this app first, even when the
    // drawable's name has nothing in common with the app name. Typing a custom query turns
    // this off (null) and the search becomes pure text matching.
    componentMatch: InstalledApplication? = null,
    // drawableName is the raw resource name (e.g. "bee_calendar_6") — used to derive the
    // calendar prefix when the user opts into day rotation.
    onIconSelect: (ResourceDrawable, IconPack, String) -> Unit,
    onTextTypeChange: (TextType) -> Unit,
    // Text-source extras: the CUSTOM type's string, the letter-case transform and the font.
    customText: String = "",
    onCustomTextChange: (String) -> Unit = {},
    textCase: TextCase = TextCase.AS_IS,
    onTextCaseChange: (TextCase) -> Unit = {},
    fontPath: String = "",
    onFontPathChange: (String) -> Unit = {},
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
    // In-flight pack-row loads; > 0 (or a pending debounce) means the search is still resolving.
    var activeLoads by remember { mutableIntStateOf(0) }
    val trackLoad: (Boolean) -> Unit = remember { { loading -> activeLoads += if (loading) 1 else -1 } }
    val busy = activeLoads > 0 || searchQuery != debouncedQuery
    LaunchedEffect(busy) { onBusyChange(busy) }
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
        val base = iconPacks
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

    // Reorder once the match results settle: never while a visible row is still loading (the
    // list must not move under the user without a loading indicator on screen), and only after
    // a short settle so one straggler pack doesn't shuffle the list repeatedly.
    LaunchedEffect(packMatches, busy) {
        if (packMatches.isEmpty() || busy) return@LaunchedEffect
        delay(450)
        // Packs that have a matching icon float to the top; among equals, the chosen pack sort
        // (distinctPacks' order) is the stable tiebreaker.
        val baseIndex = distinctPacks.withIndex().associate { it.value.packageName to it.index }
        val newOrder = distinctPacks.sortedWith(
            compareByDescending<IconPack> { packMatches[it.packageName] != false }
                .thenBy { baseIndex[it.packageName] ?: Int.MAX_VALUE }
        )
        if (newOrder.map { it.packageName } != orderedPacks.map { it.packageName }) {
            // Mihon-style anchoring: keep the viewport at the same on-screen position instead of
            // following the pack that was at the top (Lazy lists re-anchor by key by default, which
            // dragged the user down whenever "their" pack sank in the new order).
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            orderedPacks = newOrder
            if (index == 0 && offset == 0) {
                listState.scrollToItem(0)
            } else {
                listState.requestScrollToItem(index, offset)
            }
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
                        contentPadding = contentPadding,
                        iconPack = detailPack,
                        options = options,
                        sortOrder = sortOrder,
                        query = debouncedQuery,
                        component = componentMatch,
                        onLoadingChange = trackLoad,
                        selectedResourceId = selectedResourceId.takeIf { detailPack.packageName == options.primaryIconPack },
                        selectedCalendarPrefix = selectedCalendarPrefix?.takeIf { detailPack.packageName == options.primaryIconPack },
                        gridState = gridState,
                        onBack = { onExpandedPackChange(null) },
                        onSelect = { resource, _, drawableName -> onIconSelect(resource, detailPack, drawableName) }
                    )
                } else if (!contentReady) {
                    // Hold off mounting the (heavy) pack browser until the dialog has
                    // finished opening, so the open animation stays smooth
                    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                        WavyLoadingBar(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val headerInset = contentPadding.calculateTopPadding()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .drawVerticalScrollbar(listState, topInset = headerInset),
                            contentPadding = PaddingValues(top = headerInset, bottom = 16.dp)
                        ) {
                            // One item per pack (header + icons together), like Mihon's source rows:
                            // a single animateItem moves the whole section as one unit instead of the
                            // header and the icon row springing independently.
                            items(orderedPacks, key = { it.packageName }) { pack ->
                                Column(Modifier.animateItem()) {
                                    PackSectionHeader(pack) { onExpandedPackChange(pack) }
                                    PackIconsRow(
                                        iconPack = pack,
                                        options = options,
                                        sortOrder = sortOrder,
                                        query = debouncedQuery,
                                        component = componentMatch,
                                        onLoadingChange = trackLoad,
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
            Source.APPLICATION_ICON -> Box(Modifier.fillMaxSize().padding(contentPadding)) { ApplicationIconVariant(
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
            ) }
            Source.APPLICATION_NAME -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextTypeDropdown(R.string.textType, textType, includeCustom = true) { onTextTypeChange(it) }
                androidx.compose.animation.AnimatedVisibility(visible = textType == TextType.CUSTOM) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = onCustomTextChange,
                        label = { Text(stringResource(R.string.textCustomLabel)) },
                        placeholder = { Text(stringResource(R.string.textCustomHint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                FontPickerRow(selectedPath = fontPath, onChange = onFontPathChange)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.textCaseLabel),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // Literal glyphs on purpose — "Aa/AA/aa" IS the meaning, in any language.
                    FilterChip(
                        selected = textCase == TextCase.AS_IS,
                        onClick = { onTextCaseChange(TextCase.AS_IS) },
                        label = { Text("Aa") }
                    )
                    FilterChip(
                        selected = textCase == TextCase.UPPER,
                        onClick = { onTextCaseChange(TextCase.UPPER) },
                        label = { Text("AA") }
                    )
                    FilterChip(
                        selected = textCase == TextCase.LOWER,
                        onClick = { onTextCaseChange(TextCase.LOWER) },
                        label = { Text("aa") }
                    )
                }
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
        shape = InnerShape,
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
            .clip(InnerShape)
            .background(background)
            .border(if (selected) 2.dp else 0.5.dp, ring, InnerShape)
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

