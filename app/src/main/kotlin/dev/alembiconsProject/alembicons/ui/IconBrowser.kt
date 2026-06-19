@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.alembiconsProject.alembicons.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.MainActivity
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
    val context = getCurrentContext()
    var searchQuery by rememberSaveable { mutableStateOf(appName) }
    var debouncedQuery by rememberSaveable { mutableStateOf(appName) }
    var sortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var expandedPack by remember { mutableStateOf<IconPack?>(null) }
    var collapsed by remember { mutableStateOf(false) }
    val distinctPacks = remember(iconPacks) { iconPacks.distinctBy { it.packageName } }
    // Packages with at least one icon matching the query (null while still computing, or
    // when not searching). Matching is by drawable name — cheap — so the list settles in
    // one pass and keeps a stable order, rather than reordering as heavy previews finish.
    var matchingPackages by remember { mutableStateOf<Set<String>?>(null) }

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

    // When the query settles: jump back to the top (so clearing or changing it never
    // leaves you scrolled into a moved pack) and recompute which packs match — off the
    // main thread, by name only, in a single pass. The pack order never changes.
    LaunchedEffect(debouncedQuery, sortOrder, distinctPacks) {
        listState.scrollToItem(0)
        if (debouncedQuery.isBlank()) {
            matchingPackages = null
            return@LaunchedEffect
        }
        matchingPackages = null
        val appMan = ApplicationManager(context)
        matchingPackages = withContext(Dispatchers.Default) {
            distinctPacks.filter { pack ->
                try {
                    filteredSortedPackNames(appMan, pack.packageName, debouncedQuery, sortOrder).isNotEmpty()
                } catch (_: Exception) {
                    false
                }
            }.map { it.packageName }.toSet()
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
                        Icon(Icons.Filled.Sort, "Sort", tint = MaterialTheme.colorScheme.primary)
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
                    val searching = debouncedQuery.isNotBlank()
                    val displayedPacks = if (searching) {
                        distinctPacks.filter { matchingPackages?.contains(it.packageName) == true }
                    } else {
                        distinctPacks
                    }

                    when {
                        // Still working out which packs match the query
                        searching && matchingPackages == null -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                LinearWavyProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 32.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                        displayedPacks.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.noIconsFound),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                displayedPacks.forEach { pack ->
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
                                                onMore = { expandedPack = pack }
                                            ) { resource, _ ->
                                                onIconSelect(resource, pack)
                                            }
                                        }
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
        PackIcon(iconPack.packageName, 24.dp)
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

@Composable
private fun PackIcon(packageName: String, size: androidx.compose.ui.unit.Dp) {
    val context = getCurrentContext()
    // Decode off the main thread — doing it in remember{} blocked the UI thread for
    // every pack header while the editor dialog was opening
    var packIcon by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(packageName) {
        packIcon = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toSafeBitmapOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }

    val icon = packIcon
    if (icon != null) {
        Image(
            painter = BitmapPainter(icon.asImageBitmap()),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    } else {
        Surface(
            modifier = Modifier.size(4.dp, size - 4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.primary
        ) {}
    }
}

const val PACK_ROW_LIMIT = 30
// Hard cap for the full-pack grid — huge packs (e.g. Arcticons) have thousands of icons
// and eagerly generated previews for all of them would run out of memory.
const val PACK_DETAIL_LIMIT = 400

/** Drawable names of [packageName] matching [query], sorted by [sortOrder]. */
private fun filteredSortedPackNames(
    appMan: ApplicationManager,
    packageName: String,
    query: String,
    sortOrder: IconSortOrder
): List<String> {
    val allNames = appMan.getIconPackDrawableNames(packageName)
    val formattedQuery = query.lowercase().trim().replace(' ', '_')
    val matching = if (formattedQuery.isEmpty()) {
        allNames
    } else {
        allNames.filter { it.contains(formattedQuery) }
    }
    return when (sortOrder) {
        IconSortOrder.NAME_ASC -> matching.sortedBy { it }
        IconSortOrder.NAME_DESC -> matching.sortedByDescending { it }
    }
}

/**
 * A pack icon ready to show: the source [resource] (passed back on tap) and a
 * [preview] bitmap rasterised once on a background thread. Rendering this bitmap
 * is far cheaper per frame than rebuilding a vector painter for every grid item.
 */
data class PackIconPreview(
    val resource: ResourceDrawable,
    val drawable: IconPackDrawable,
    val preview: ImageBitmap
)

// Previews only ever render at ~56-64dp; a 96px bitmap covers that on the highest
// densities while keeping the full-pack grid (up to PACK_DETAIL_LIMIT items) within
// a sane memory budget — a full 256px raster each would be ~100 MB for 400 icons.
private const val PREVIEW_PX = 96

private fun Bitmap.scaledPreview(max: Int = PREVIEW_PX): Bitmap {
    val biggest = maxOf(width, height)
    if (biggest <= max) return this
    val scale = max.toFloat() / biggest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true
    )
}

/** Generates the preview icons for the given drawable [names] of a pack. */
private fun loadPackIconPairs(
    appMan: ApplicationManager,
    activity: MainActivity,
    packageName: String,
    options: GenerationOptions,
    names: List<String>
): List<PackIconPreview> {
    val ids = appMan.getIconPackDrawableIds(packageName, names)
    val drawables = appMan.getIconPackDrawables(packageName, ids)
    val exportDrawables = activity.appProvider.getIconPackIcons(packageName, options, drawables)
    return exportDrawables.entries
        .filter { it.value != null }
        .distinctBy { it.key.resourceId }
        .map { PackIconPreview(it.key, it.value!!, it.value!!.toBitmap().scaledPreview().asImageBitmap()) }
}

@Composable
fun PackIconsRow(
    iconPack: IconPack,
    options: GenerationOptions,
    sortOrder: IconSortOrder,
    query: String = "",
    onMore: (() -> Unit)? = null,
    onResult: (hasMatches: Boolean) -> Unit = {},
    onSelect: (ResourceDrawable, IconPackDrawable) -> Unit
) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    var iconPairs by remember { mutableStateOf<List<PackIconPreview>>(emptyList()) }
    var moreCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(iconPack.packageName, sortOrder, query) {
        isLoading = true
        val loaded = withContext(Dispatchers.Default) {
            try {
                val appMan = ApplicationManager(context)
                val sortedNames = filteredSortedPackNames(appMan, iconPack.packageName, query, sortOrder)
                moreCount = (sortedNames.size - PACK_ROW_LIMIT).coerceAtLeast(0)
                loadPackIconPairs(appMan, activity, iconPack.packageName, options, sortedNames.take(PACK_ROW_LIMIT))
            } catch (_: Exception) {
                // A malformed icon pack must not crash the browser
                moreCount = 0
                emptyList()
            }
        }
        iconPairs = loaded
        isLoading = false
        onResult(loaded.isNotEmpty())
    }

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.noIconsFound),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(iconPairs, key = { _, item -> item.resource.resourceId }) { _, item ->
                Image(
                    bitmap = item.preview,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(4.dp)
                        .tappableIcon { onSelect(item.resource, item.drawable) }
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
    onBack: () -> Unit,
    onCollapsedChange: (Boolean) -> Unit = {},
    onSelect: (ResourceDrawable, IconPackDrawable) -> Unit
) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    var iconPairs by remember { mutableStateOf<List<PackIconPreview>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val gridState = rememberLazyGridState()
    val gridScrolled by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 60 }
    }
    LaunchedEffect(gridScrolled) {
        onCollapsedChange(gridScrolled)
    }

    LaunchedEffect(iconPack.packageName, sortOrder, query) {
        isLoading = true
        iconPairs = emptyList()
        withContext(Dispatchers.Default) {
            try {
                val appMan = ApplicationManager(context)
                val sortedNames = filteredSortedPackNames(appMan, iconPack.packageName, query, sortOrder)
                // Load in chunks so the grid fills progressively instead of blocking
                for (chunk in sortedNames.take(PACK_DETAIL_LIMIT).chunked(40)) {
                    coroutineContext.ensureActive()
                    val pairs = loadPackIconPairs(appMan, activity, iconPack.packageName, options, chunk)
                    iconPairs = (iconPairs + pairs).distinctBy { it.resource.resourceId }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A malformed icon pack must not crash the browser
            }
        }
        isLoading = false
    }

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
            PackIcon(iconPack.packageName, 28.dp)
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.noIconsFound),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)
            ) {
                items(iconPairs, key = { it.resource.resourceId }) { item ->
                    Image(
                        bitmap = item.preview,
                        contentDescription = null,
                        modifier = Modifier
                            .animateItem()
                            .padding(4.dp)
                            .size(56.dp)
                            .tappableIcon { onSelect(item.resource, item.drawable) }
                    )
                }
            }
        }
    }
}
