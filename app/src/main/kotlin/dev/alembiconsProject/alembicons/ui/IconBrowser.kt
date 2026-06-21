@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.alembiconsProject.alembicons.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay

@Composable
fun CreateTab(
    source: Source,
    iconPacks: List<IconPack>,
    options: GenerationOptions,
    textType: TextType,
    appName: String,
    onIconSelect: (ResourceDrawable, IconPack) -> Unit,
    onTextTypeChange: (TextType) -> Unit,
    onCollapsedChange: (Boolean) -> Unit = {},
    contentReady: Boolean = true
) {
    var searchQuery by rememberSaveable { mutableStateOf(appName) }
    var debouncedQuery by rememberSaveable { mutableStateOf(appName) }
    var sortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
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
        if (source == Source.ICON_PACK) {
            // Search bar slides away while the icon list is scrolled to save space
            // Reveal/hide the search bar with a soft, low-stiffness spring so it eases
            // in gradually on scroll-up instead of snapping open all at once
            AnimatedVisibility(
                visible = !collapsed,
                enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) +
                        fadeIn(spring(stiffness = Spring.StiffnessLow)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) +
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    shape = CircleShape,
                    placeholder = { Text(stringResource(R.string.searchIcons)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("A → Z") },
                            onClick = { sortOrder = IconSortOrder.NAME_ASC; showSortMenu = false },
                            leadingIcon = if (sortOrder == IconSortOrder.NAME_ASC) {
                                { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                        DropdownMenuItem(
                            text = { Text("Z → A") },
                            onClick = { sortOrder = IconSortOrder.NAME_DESC; showSortMenu = false },
                            leadingIcon = if (sortOrder == IconSortOrder.NAME_DESC) {
                                { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        when (source) {
            Source.ICON_PACK -> {
                val detailPack = expandedPack
                if (detailPack != null) {
                    PackDetailGrid(
                        iconPack = detailPack,
                        options = options,
                        sortOrder = sortOrder,
                        query = debouncedQuery,
                        onBack = { expandedPack = null },
                        onCollapsedChange = { collapsed = it },
                        onSelect = { resource, _ -> onIconSelect(resource, detailPack) }
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
                                        onMore = { expandedPack = pack },
                                        onResult = { hasMatches ->
                                            packMatches = packMatches + (pack.packageName to hasMatches)
                                        }
                                    ) { resource, _ ->
                                        onIconSelect(resource, pack)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Source.APPLICATION_ICON -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.applicationIcon),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
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

enum class IconSortOrder { NAME_ASC, NAME_DESC }
