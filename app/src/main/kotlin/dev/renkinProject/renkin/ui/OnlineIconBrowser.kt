@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.OnlineIconBrowserViewModel
import dev.renkinProject.renkin.OnlineIconImport
import dev.renkinProject.renkin.OnlineIconPreview
import dev.renkinProject.renkin.OnlineImageImport
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.online.IconifyCollection
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.vector.ColorDecoder
import dev.renkinProject.renkin.vector.SvgVectorImporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Fullscreen browser over the Iconify catalogue (200+ FOSS icon sets): a filterable set list
 * (keyword, category, colour-vs-monochrome — mirroring iconify.design's own filters), then
 * the tapped set's searchable grid. Only the set list, one set's name index and the
 * visible/tapped SVGs are downloaded (cached on disk). Tapping an icon opens a close-up
 * detail dialog first — browsing without committing. [onPicked] receives the parsed SVG of
 * an editable icon; [onPickedImage] the full-size raster of a preview-only one (gradients
 * etc.). Both get the public source URL, stored as the icon's attribution reference.
 */
@Composable
internal fun OnlineIconBrowserDialog(
    onPicked: (SvgVectorImporter.ImportedSvg, sourceUrl: String) -> Unit,
    onPickedImage: (OnlineImageImport, sourceUrl: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: OnlineIconBrowserViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val notImportableMessage = stringResource(R.string.onlineIconNotImportable)
    val loadFailedMessage = stringResource(R.string.onlineIconLoadFailed)
    val rasterTintArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    // null = the set list; non-null = that set's icon grid (back returns to the list).
    var importing by remember { mutableStateOf(false) }
    // The icon whose close-up detail dialog is open.
    var detailIcon by remember { mutableStateOf<OnlineIcon?>(null) }
    LaunchedEffect(Unit) { viewModel.beginSession() }
    val back: () -> Unit = {
        if (!importing) {
            if (viewModel.selectedCollection != null) {
                viewModel.backToCollections()
            } else {
                viewModel.endSession()
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = back, enabled = !importing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = viewModel.selectedCollection?.name ?: stringResource(R.string.onlineIconsTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (importing) {
                        LoadingIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }

                // Every surface an icon can be tapped on (set grid and the cross-set search
                // results) opens the close-up detail first — importing happens from there.
                val pickIcon: (OnlineIcon) -> Unit = { icon ->
                    if (!importing) detailIcon = icon
                }
                val current = viewModel.selectedCollection
                if (current == null) {
                    CollectionList(viewModel, enabled = !importing, onPick = pickIcon)
                } else {
                    CollectionGrid(
                        viewModel = viewModel,
                        enabled = !importing,
                        onPick = pickIcon
                    )
                }
            }
        }
    }

    detailIcon?.let { icon ->
        OnlineIconDetailDialog(
            icon = icon,
            viewModel = viewModel,
            importing = importing,
            onDismiss = { if (!importing) detailIcon = null },
            onUseVector = {
                importing = true
                scope.launch {
                    val outcome = try {
                        viewModel.importIcon(icon)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        OnlineIconImport.LoadFailed
                    } finally {
                        importing = false
                    }
                    when (outcome) {
                        is OnlineIconImport.Imported -> {
                            viewModel.endSession()
                            onPicked(outcome.svg, icon.svgUrl)
                        }
                        OnlineIconImport.NotImportable -> toaster.show(notImportableMessage)
                        OnlineIconImport.LoadFailed -> toaster.show(loadFailedMessage)
                    }
                }
            },
            onUseImage = {
                importing = true
                scope.launch {
                    val imported = try {
                        viewModel.importImage(icon, rasterTintArgb)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } finally {
                        importing = false
                    }
                    if (imported == null) {
                        toaster.show(loadFailedMessage)
                    } else {
                        viewModel.endSession()
                        onPickedImage(imported, icon.svgUrl)
                    }
                }
            }
        )
    }
}

/**
 * Close-up look at one icon before committing: a large faithful preview, the set + licence
 * line, and a single action — **Use icon** imports editable paths into the vector editor,
 * **Use as image** imports a preview-only icon (gradients, clips) as a full-size picture.
 * Dismissing just returns to browsing; nothing is imported by merely looking.
 */
@Composable
private fun OnlineIconDetailDialog(
    icon: OnlineIcon,
    viewModel: OnlineIconBrowserViewModel,
    importing: Boolean,
    onDismiss: () -> Unit,
    onUseVector: () -> Unit,
    onUseImage: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurface
    // Bumping the attempt key re-runs the fetch after a connection failure.
    var attempt by remember(icon) { mutableStateOf(0) }
    var failed by remember(icon) { mutableStateOf(false) }
    val preview by produceState<OnlineIconPreview?>(null, icon, tint, attempt) {
        failed = false
        value = viewModel.detailPreview(icon, tint.toArgb())
        failed = value == null
    }
    val collection = remember(viewModel.collections, icon.prefix) {
        viewModel.collections?.firstOrNull { it.prefix == icon.prefix }
    }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(icon.label) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Box(
                        modifier = Modifier
                            .padding(16.dp)
                            .size(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            failed -> EmptyState(
                                icon = Icons.Filled.CloudOff,
                                text = stringResource(R.string.onlineIconLoadFailed),
                                actionLabel = stringResource(R.string.reload),
                                onAction = { attempt++ }
                            )
                            preview == null -> LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                            else -> OnlinePreviewImage(preview, tint, contentDescription = icon.label)
                        }
                    }
                }
                collection?.let { set ->
                    Text(
                        text = set.name + " · " +
                            set.license.ifEmpty { stringResource(R.string.onlineUnknownLicense) },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                if (preview is OnlineIconPreview.PreviewOnly) {
                    Text(
                        text = boldStringResource(R.string.onlineIconImageNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            when (preview) {
                is OnlineIconPreview.Editable -> TextButton(onClick = onUseVector, enabled = !importing) {
                    Text(stringResource(R.string.onlineIconUse))
                }
                is OnlineIconPreview.PreviewOnly -> TextButton(onClick = onUseImage, enabled = !importing) {
                    Text(stringResource(R.string.onlineIconUseAsImage))
                }
                null -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text(stringResource(R.string.dismiss))
            }
        }
    )
}

/**
 * The Iconify set list with the site's filters: a keyword field, category chips, and an
 * all/colour/monochrome palette toggle. Rows carry name, size, licence and live sample
 * previews (lazily fetched, disk-cached).
 */
@Composable
private fun CollectionList(
    viewModel: OnlineIconBrowserViewModel,
    enabled: Boolean,
    onPick: (OnlineIcon) -> Unit
) {
    val collections = viewModel.collections
    val query = viewModel.collectionQuery
    val category = viewModel.category
    val palette = viewModel.palette
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.collectionListIndex,
        initialFirstVisibleItemScrollOffset = viewModel.collectionListOffset
    )
    val filterScrollState = rememberScrollState(viewModel.filterRowOffset)

    val categories = remember(collections) {
        collections.orEmpty().mapNotNull { it.category.takeIf(String::isNotEmpty) }
            .distinct().sorted()
    }
    val filtered = remember(collections, query, category, palette) {
        val needle = query.trim().lowercase()
        collections.orEmpty().filter { set ->
            (needle.isEmpty() || needle in set.name.lowercase() || needle in set.prefix) &&
                (category == null || set.category == category) &&
                (palette == null || set.palette == palette)
        }
    }

    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SearchField(
            value = query,
            onValueChange = viewModel::onCollectionQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.onlineFilterSets)
        )
    }
    // Two or more characters flip the field from "filter the set list" to a cross-set icon
    // search through the API — results show the icon with the set it comes from, like the
    // search on iconify.design.
    if (viewModel.searchActive) {
        HorizontalDivider()
        SearchResults(viewModel, enabled, onPick)
        return
    }
    // Palette toggle + category chips in one horizontally scrolling row, like the site's
    // filter bar. The palette chips sit first — they're the ones users reach for.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(filterScrollState)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = palette == true,
            onClick = { viewModel.palette = if (palette == true) null else true },
            label = { Text(stringResource(R.string.onlineFilterColor)) }
        )
        FilterChip(
            selected = palette == false,
            onClick = { viewModel.palette = if (palette == false) null else false },
            label = { Text(stringResource(R.string.onlineFilterMono)) }
        )
        if (categories.isNotEmpty()) {
            Text(
                text = "·",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        categories.forEach { candidate ->
            FilterChip(
                selected = category == candidate,
                onClick = { viewModel.category = if (category == candidate) null else candidate },
                label = { Text(candidate) }
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.onlinePoweredBy),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinkText(text = "iconify.design", url = "https://iconify.design")
    }
    HorizontalDivider()

    when {
        collections == null && !viewModel.collectionsFailed -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
        }

        viewModel.collectionsFailed -> EmptyState(
            icon = Icons.Filled.CloudOff,
            text = stringResource(R.string.onlineIconsLoadFailed),
            modifier = Modifier.fillMaxSize(),
            actionLabel = stringResource(R.string.reload),
            onAction = viewModel::retryCollections
        )

        filtered.isEmpty() -> EmptyState(
            icon = Icons.Filled.SearchOff,
            text = stringResource(R.string.noIconsFound),
            modifier = Modifier.fillMaxSize()
        )

        else -> {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .drawVerticalScrollbar(listState)
            ) {
                items(filtered, key = { it.prefix }) { set ->
                    CollectionRow(set, viewModel) {
                        viewModel.openCollection(
                            set,
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                            filterScrollState.value
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cross-set search results as a grid of icons, each labelled with its name and the set it
 * belongs to — tapping imports it directly, no need to know which set to look in.
 */
@Composable
private fun SearchResults(
    viewModel: OnlineIconBrowserViewModel,
    enabled: Boolean,
    onPick: (OnlineIcon) -> Unit
) {
    val results = viewModel.searchResults
    val setNames = remember(viewModel.collections) {
        viewModel.collections.orEmpty().associate { it.prefix to it.name }
    }
    when {
        viewModel.searching || (results == null && !viewModel.searchFailed) -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
        }

        viewModel.searchFailed -> EmptyState(
            icon = Icons.Filled.CloudOff,
            text = stringResource(R.string.onlineIconsLoadFailed),
            modifier = Modifier.fillMaxSize(),
            actionLabel = stringResource(R.string.reload),
            onAction = viewModel::retrySearch
        )

        results.isNullOrEmpty() -> EmptyState(
            icon = Icons.Filled.SearchOff,
            text = stringResource(R.string.noIconsFound),
            modifier = Modifier.fillMaxSize()
        )

        else -> {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .drawVerticalScrollbar(gridState)
            ) {
                items(results, key = { "${it.prefix}/${it.name}" }) { icon ->
                    OnlineIconTile(
                        icon,
                        viewModel,
                        enabled = enabled,
                        subLabel = setNames[icon.prefix] ?: icon.prefix
                    ) { onPick(icon) }
                }
            }
        }
    }
}

/** One set row: name, size + licence line, and up to three live sample previews. */
@Composable
private fun CollectionRow(
    set: IconifyCollection,
    viewModel: OnlineIconBrowserViewModel,
    onOpen: () -> Unit
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            set.samples.take(3).forEach { sample ->
                SamplePreview(OnlineIcon(set.prefix, sample), viewModel)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 6.dp, end = 4.dp)
            ) {
                Text(
                    text = set.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.onlineSetCount, set.total) + " · " +
                        set.license.ifEmpty { stringResource(R.string.onlineUnknownLicense) },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (set.license.isEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** A small lazily-fetched sample icon for a set row. */
@Composable
private fun SamplePreview(icon: OnlineIcon, viewModel: OnlineIconBrowserViewModel) {
    val tint = MaterialTheme.colorScheme.onSurface
    val preview by produceState<OnlineIconPreview?>(null, icon, tint) {
        value = viewModel.preview(icon, tint.toArgb())
    }
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        OnlinePreviewImage(preview, tint, contentDescription = null)
    }
}

/** One set's searchable icon grid, index loaded (and disk-cached) on entry. */
@Composable
private fun CollectionGrid(
    viewModel: OnlineIconBrowserViewModel,
    enabled: Boolean,
    onPick: (OnlineIcon) -> Unit
) {
    val icons = viewModel.icons
    val query = viewModel.iconQuery
    val filtered = remember(icons, query) {
        val needle = query.trim().lowercase().replace(' ', '-')
        val list = icons.orEmpty()
        if (needle.isEmpty()) list else list.filter { needle in it.name }
    }

    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SearchField(
            value = query,
            onValueChange = { viewModel.iconQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.searchIcons)
        )
    }
    HorizontalDivider()

    when {
        icons == null && !viewModel.iconsFailed -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
        }

        viewModel.iconsFailed -> EmptyState(
            icon = Icons.Filled.CloudOff,
            text = stringResource(R.string.onlineIconsLoadFailed),
            modifier = Modifier.fillMaxSize(),
            actionLabel = stringResource(R.string.reload),
            onAction = viewModel::retryIcons
        )

        filtered.isEmpty() -> EmptyState(
            icon = Icons.Filled.SearchOff,
            text = stringResource(R.string.noIconsFound),
            modifier = Modifier.fillMaxSize()
        )

        else -> {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .drawVerticalScrollbar(gridState)
            ) {
                items(filtered, key = { "${it.prefix}/${it.name}" }) { icon ->
                    OnlineIconTile(icon, viewModel, enabled = enabled) { onPick(icon) }
                }
            }
        }
    }
}

/**
 * One grid tile: lazily fetches (disk-cached) and renders the icon's SVG, name below —
 * plus the set name ([subLabel]) in the cross-set search results.
 */
@Composable
private fun OnlineIconTile(
    icon: OnlineIcon,
    viewModel: OnlineIconBrowserViewModel,
    enabled: Boolean,
    subLabel: String? = null,
    onClick: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val preview by produceState<OnlineIconPreview?>(null, icon, tint) {
        value = viewModel.preview(icon, tint.toArgb())
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (preview == null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = dev.renkinProject.renkin.ui.theme.InnerShape,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {}
            } else {
                OnlinePreviewImage(preview, tint, contentDescription = icon.label)
            }
            // Faithful raster previews can't become editable vectors — flag them so the
            // rejection toast on tap isn't the first hint.
            if (preview is OnlineIconPreview.PreviewOnly) {
                Surface(
                    shape = SwatchShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Icon(
                        imageVector = Icons.Filled.EditOff,
                        contentDescription = stringResource(R.string.onlineIconNotImportable),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(2.dp)
                            .size(12.dp)
                    )
                }
            }
        }
        Text(
            text = icon.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )
        if (subLabel != null) {
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Renders either preview kind at the size of its container: editable icons as tinted
 * vectors, preview-only icons as their faithful raster. Nothing while still loading.
 */
@Composable
private fun OnlinePreviewImage(
    preview: OnlineIconPreview?,
    tint: Color,
    contentDescription: String?
) {
    when (preview) {
        is OnlineIconPreview.Editable -> {
            val image = remember(preview, tint) { preview.svg.toPreviewVector(tint) }
            if (image != null) {
                Image(
                    painter = rememberVectorPainter(image),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        is OnlineIconPreview.PreviewOnly -> Image(
            bitmap = preview.bitmap,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize()
        )

        null -> Unit
    }
}

/**
 * A preview vector from an imported SVG — same path semantics as the vector editor's import
 * (fill vs stroke with authored widths). Multicolour sets render their own per-path colours;
 * monochrome paths take the theme [tint].
 */
private fun SvgVectorImporter.ImportedSvg.toPreviewVector(
    tint: Color
): ImageVector? = runCatching {
    val builder = ImageVector.Builder(
        defaultWidth = 44.dp,
        defaultHeight = 44.dp,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
    for (path in paths) {
        val nodes = PathParser().parsePathString(path.pathData).toNodes()
        val solid = SolidColor(
            path.color?.let { raw ->
                ColorDecoder.decodeSvgCss(raw).takeIf { it.isSpecified }
            }?.let { it.copy(alpha = it.alpha * path.alpha) }
                ?: tint.copy(alpha = tint.alpha * path.alpha)
        )
        if (path.filled) {
            builder.addPath(nodes, pathFillType = path.fillType, fill = solid)
        } else {
            builder.addPath(
                nodes,
                stroke = solid,
                strokeLineWidth = path.strokeWidth ?: (viewportHeight / 24f),
                strokeLineCap = path.strokeLineCap,
                strokeLineJoin = path.strokeLineJoin
            )
        }
    }
    builder.build()
}.getOrNull()
