@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.feature.online

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.online.IconifyCollection
import dev.renkinProject.renkin.data.online.DownloadedIconSet
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.ui.EmptyState
import dev.renkinProject.renkin.ui.ConfirmDialog
import dev.renkinProject.renkin.ui.DialogBadge
import dev.renkinProject.renkin.ui.DialogCard
import dev.renkinProject.renkin.ui.DialogFactRow
import dev.renkinProject.renkin.ui.DialogPrimaryAction
import dev.renkinProject.renkin.ui.DialogSecondaryAction
import dev.renkinProject.renkin.ui.DialogTone
import dev.renkinProject.renkin.ui.LinkText
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.MessageDialog
import dev.renkinProject.renkin.ui.RenkinAlertDialog
import dev.renkinProject.renkin.ui.SearchField
import dev.renkinProject.renkin.ui.WavyProgressBar
import dev.renkinProject.renkin.ui.boldStringResource
import dev.renkinProject.renkin.ui.drawVerticalScrollbar
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.vector.ColorDecoder
import dev.renkinProject.renkin.vector.SvgVectorImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val rateLimitUntil by viewModel.rateLimitUntil.collectAsStateWithLifecycle()

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
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (importing) {
                        LoadingIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                    viewModel.selectedCollection?.let { DownloadedSetMenu(it, viewModel) }
                }

                OnlineRateLimitNotice(rateLimitUntil)

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

    viewModel.downloadOffer?.let { DownloadSetDialog(it, viewModel) }
    viewModel.pendingDelete?.let { set ->
        ConfirmDialog(
            title = stringResource(R.string.onlineSetDeleteTitle, set.name),
            text = stringResource(R.string.onlineSetDeleteBody),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            confirmLabel = stringResource(R.string.onlineSetDelete)
        )
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
    val result by rememberOnlineIconPreview(icon, viewModel, tint, attempt, detail = true)
    val preview = (result as? OnlinePreviewLoadState.Ready)?.preview
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
                            result == OnlinePreviewLoadState.Failed -> EmptyState(
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
    val palette = viewModel.palette
    val downloaded by viewModel.downloadedSets.collectAsStateWithLifecycle()
    val downloadedOnly = viewModel.downloadedOnly
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.collectionListIndex,
        initialFirstVisibleItemScrollOffset = viewModel.collectionListOffset
    )
    val filterScrollState = rememberScrollState(viewModel.filterRowOffset)
    val visibleKeys by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }

    val filtered = remember(collections, query, palette, downloadedOnly, downloaded) {
        val needle = query.trim().lowercase()
        collections.orEmpty().filter { set ->
            (needle.isEmpty() || needle in set.name.lowercase() || needle in set.prefix) &&
                (palette == null || set.palette == palette) &&
                (!downloadedOnly || set.prefix in downloaded || viewModel.isBuiltIn(set))
        }
    }
    // Categories and their sets keep the API order, as on icon-sets.iconify.design.
    val groups = remember(filtered) { filtered.groupBy { it.category } }
    val collapsed = viewModel.collapsedCategories

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(filterScrollState)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = downloadedOnly,
            onClick = { viewModel.downloadedOnly = !downloadedOnly },
            label = { Text(stringResource(R.string.onlineFilterDownloaded)) }
        )
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
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (downloadedOnly && downloaded.isNotEmpty()) {
            val context = LocalContext.current
            val bytes = downloaded.values.sumOf { it.bytes }
            Text(
                text = pluralStringResource(
                    R.plurals.onlineDownloadedSummary,
                    downloaded.size,
                    downloaded.size,
                    remember(bytes) { Formatter.formatShortFileSize(context, bytes) }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.onlinePoweredBy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinkText(text = "iconify.design", url = "https://iconify.design")
        }
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
                modifier = Modifier
                    .fillMaxSize()
                    .drawVerticalScrollbar(listState)
            ) {
                // Each category reads as one card (like Advanced options), built from separate
                // lazy items whose shapes join: the header carries the top corners, the last set
                // the bottom ones.
                groups.forEach { (category, sets) ->
                    val expanded = category !in collapsed
                    item(key = "category:$category", contentType = "categoryHeader") {
                        CategoryHeader(
                            title = category.ifEmpty { stringResource(R.string.onlineCategoryOther) },
                            count = sets.size,
                            expanded = expanded,
                            onToggle = { viewModel.toggleCategory(category) },
                            modifier = Modifier.animateItem()
                        )
                    }
                    if (expanded) itemsIndexed(sets, key = { _, set -> set.prefix }, contentType = { _, _ -> "set" }) { index, set ->
                        CollectionRow(
                            set,
                            viewModel,
                            loadPreviews = set.prefix in visibleKeys && !listState.isScrollInProgress,
                            badge = setBadge(set, viewModel, downloaded),
                            isFirst = index == 0,
                            isLast = index == sets.lastIndex,
                            modifier = Modifier.animateItem()
                        ) {
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
            val visibleKeys by remember(gridState) {
                derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
            }
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
                        subLabel = setNames[icon.prefix] ?: icon.prefix,
                        loadPreview = "${icon.prefix}/${icon.name}" in visibleKeys && !gridState.isScrollInProgress
                    ) { onPick(icon) }
                }
            }
        }
    }
}

/** One set row inside its category card: name, size + licence line, and up to three live sample previews. */
@Composable
private fun CollectionRow(
    set: IconifyCollection,
    viewModel: OnlineIconBrowserViewModel,
    loadPreviews: Boolean,
    badge: String?,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    Surface(
        shape = if (isLast) CategoryBottomShape else RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (isLast) CATEGORY_GAP else 0.dp)
    ) {
        Column {
            if (!isFirst) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                set.samples.take(3).forEach { sample ->
                    SamplePreview(OnlineIcon(set.prefix, sample), viewModel, loadPreviews)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 6.dp, end = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = set.name,
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        badge?.let { SetSourceBadge(it) }
                    }
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
}

/** The first row of a category card, like the Advanced options header; tapping folds the sets away. */
@Composable
private fun CategoryHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "categoryChevron"
    )
    // 0 = joined to its sets, 1 = a standalone card: the corners and the gap below morph together.
    val collapseProgress by animateFloatAsState(
        targetValue = if (expanded) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "categoryCollapse"
    )
    // The expressive spring overshoots; a negative gap or corner would crash.
    val collapse = collapseProgress.coerceIn(0f, 1f)
    Surface(
        shape = CardShape.copy(
            bottomStart = CornerSize(CATEGORY_CORNER * collapse),
            bottomEnd = CornerSize(CATEGORY_CORNER * collapse)
        ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = CATEGORY_GAP * collapse)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(
                        if (expanded) R.string.onlineCategoryCollapse else R.string.onlineCategoryExpand
                    ),
                    onClick = onToggle
                )
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Only the spacer takes weight: a weighted title would split the free width with it
            // and leave the chevron short of the set rows' chevrons.
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pluralStringResource(R.plurals.onlineCategorySetCount, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(chevronRotation)
            )
        }
    }
}

private val CATEGORY_CORNER = 20.dp // CardShape radius; the header morphs its bottom corners to it
private val CategoryBottomShape = CardShape.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
private val CATEGORY_GAP = 12.dp

/** Overflow actions of an opened downloaded set: replace it with the newest release, or remove it. */
@Composable
private fun DownloadedSetMenu(set: IconifyCollection, viewModel: OnlineIconBrowserViewModel) {
    val downloaded by viewModel.downloadedSets.collectAsStateWithLifecycle()
    if (set.prefix !in downloaded) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.moreOptions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.onlineSetDownloadLatest)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    open = false
                    viewModel.offerDownload(set)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.onlineSetDelete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    viewModel.requestDelete(set)
                }
            )
        }
    }
}

/** Confirms a download with the real size, icon count and source host before anything is fetched. */
@Composable
private fun DownloadSetDialog(offer: SetDownloadOffer, viewModel: OnlineIconBrowserViewModel) {
    val context = LocalContext.current
    MessageDialog(
        onDismissRequest = viewModel::dismissDownloadOffer,
        badge = { DialogBadge(Icons.Filled.DownloadForOffline, DialogTone.Accent) },
        eyebrow = stringResource(R.string.onlineSetDownloadEyebrow),
        title = offer.set.name,
        supporting = stringResource(if (offer.upToDate) R.string.onlineSetUpToDate else R.string.onlineSetDownloadBody),
        actions = {
            if (!offer.upToDate) {
                DialogPrimaryAction(stringResource(R.string.onlineSetDownloadAction), viewModel::confirmDownload)
            }
            DialogSecondaryAction(stringResource(R.string.dismiss), viewModel::dismissDownloadOffer)
        }
    ) {
        DialogCard {
            DialogFactRow(
                stringResource(R.string.onlineSetDownloadSize),
                remember(offer) { Formatter.formatShortFileSize(context, offer.release.iconsBytes) }
            )
            DialogFactRow(stringResource(R.string.onlineSetDownloadIcons), offer.set.total.toString())
            DialogFactRow(stringResource(R.string.onlineSetDownloadVersion), offer.release.version)
        }
    }
}

/**
 * Offers an opened online set for offline use, then shows the download progress or why it failed.
 * Hidden once the set is local.
 */
@Composable
private fun OfflineSetCard(set: IconifyCollection, state: SetDownloadState?, viewModel: OnlineIconBrowserViewModel) {
    val failed = state as? SetDownloadState.Failed
    Surface(
        shape = InnerShape,
        color = if (failed != null) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (failed != null) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (failed != null) Icons.Filled.CloudOff else Icons.Filled.DownloadForOffline,
                contentDescription = null,
                tint = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                when (state) {
                    is SetDownloadState.Downloading -> {
                        Text(
                            text = stringResource(R.string.onlineSetDownloading, (state.progress * 100).toInt()),
                            style = MaterialTheme.typography.titleSmallEmphasized.copy(fontFeatureSettings = "tnum")
                        )
                        WavyProgressBar(state.progress, Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    is SetDownloadState.Failed -> Text(
                        text = stringResource(
                            when (state.reason) {
                                SetDownloadFailure.Network -> R.string.onlineSetDownloadFailed
                                SetDownloadFailure.Damaged -> R.string.onlineSetIntegrityFailed
                                SetDownloadFailure.NotAvailable -> R.string.onlineSetNotDownloadable
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> {
                        Text(
                            text = stringResource(R.string.onlineSetDownloadCta),
                            style = MaterialTheme.typography.titleSmallEmphasized
                        )
                        Text(
                            text = stringResource(R.string.onlineSetDownloadCtaHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            when (state) {
                SetDownloadState.Preparing -> LoadingIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                is SetDownloadState.Downloading -> TextButton(onClick = { viewModel.cancelDownload(set.prefix) }) {
                    Text(stringResource(R.string.colorizeCancel))
                }
                // A set without a downloadable package keeps its explanation for the session.
                is SetDownloadState.Failed -> if (state.reason != SetDownloadFailure.NotAvailable) {
                    TextButton(onClick = { viewModel.offerDownload(set) }) {
                        Text(stringResource(R.string.onlineSetRetry))
                    }
                }
                null -> FilledTonalButton(onClick = { viewModel.offerDownload(set) }, shape = FieldShape) {
                    Text(stringResource(R.string.onlineSetDownloadAction))
                }
            }
        }
    }
}

/** The quiet source badge of a set row: progress while downloading, else Built in / Downloaded. */
@Composable
private fun setBadge(
    set: IconifyCollection,
    viewModel: OnlineIconBrowserViewModel,
    downloaded: Map<String, DownloadedIconSet>
): String? {
    (viewModel.downloadStates[set.prefix] as? SetDownloadState.Downloading)?.let {
        return stringResource(R.string.onlineSetDownloadingBadge, (it.progress * 100).toInt())
    }
    return when {
        viewModel.isBuiltIn(set) -> stringResource(R.string.onlineSetBuiltIn)
        set.prefix in downloaded -> stringResource(R.string.onlineSetDownloaded)
        else -> null
    }
}

@Composable
private fun SetSourceBadge(label: String) {
    Surface(
        shape = SwatchShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** A small lazily-fetched sample icon for a set row. */
@Composable
private fun SamplePreview(
    icon: OnlineIcon,
    viewModel: OnlineIconBrowserViewModel,
    loadPreview: Boolean
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val result by rememberOnlineIconPreview(icon, viewModel, tint, load = loadPreview)
    val preview = (result as? OnlinePreviewLoadState.Ready)?.preview
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        if (result == OnlinePreviewLoadState.Failed) {
            OnlinePreviewFailure(Modifier.size(18.dp))
        } else {
            OnlinePreviewImage(preview, tint, contentDescription = null)
        }
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
    val downloaded by viewModel.downloadedSets.collectAsStateWithLifecycle()
    viewModel.selectedCollection?.let { set ->
        val state = viewModel.downloadStates[set.prefix]
        if (state != null || (!viewModel.isBuiltIn(set) && set.prefix !in downloaded)) {
            OfflineSetCard(set, state, viewModel)
        }
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
            val visibleKeys by remember(gridState) {
                derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
            }
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
                    OnlineIconTile(
                        icon,
                        viewModel,
                        enabled = enabled,
                        loadPreview = "${icon.prefix}/${icon.name}" in visibleKeys && !gridState.isScrollInProgress
                    ) { onPick(icon) }
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
    loadPreview: Boolean,
    onClick: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val result by rememberOnlineIconPreview(icon, viewModel, tint, load = loadPreview)
    val preview = (result as? OnlinePreviewLoadState.Ready)?.preview
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (result == OnlinePreviewLoadState.Failed) {
                OnlinePreviewFailure(Modifier.size(24.dp))
            } else if (preview == null) {
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

@Composable
private fun OnlineRateLimitNotice(retryAt: Long) {
    val secondsLeft by produceState(0L, retryAt) {
        while (true) {
            val remainingMs = (retryAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            value = (remainingMs + 999L) / 1_000L
            if (remainingMs == 0L) break
            delay(minOf(1_000L, remainingMs))
        }
    }
    if (secondsLeft > 0L) {
        Text(
            text = stringResource(R.string.onlineIconsRateLimited, secondsLeft),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

private sealed interface OnlinePreviewLoadState {
    data object Loading : OnlinePreviewLoadState
    data object Failed : OnlinePreviewLoadState
    data class Ready(val preview: OnlineIconPreview) : OnlinePreviewLoadState
}

@Composable
private fun rememberOnlineIconPreview(
    icon: OnlineIcon,
    viewModel: OnlineIconBrowserViewModel,
    tint: Color,
    attempt: Int = 0,
    detail: Boolean = false,
    load: Boolean = true
): State<OnlinePreviewLoadState> {
    val loadRequested by rememberUpdatedState(load)
    val revision = viewModel.previewRevision(icon.prefix)
    return produceState<OnlinePreviewLoadState>(
        OnlinePreviewLoadState.Loading, icon, viewModel, tint, attempt, detail, revision
    ) {
        value = OnlinePreviewLoadState.Loading
        val preview = try {
            when (val cached = viewModel.cachedPreview(icon, tint.toArgb(), detail)) {
                is OnlineIconCachedPreview.Ready -> cached.preview
                OnlineIconCachedPreview.Failed -> null
                OnlineIconCachedPreview.Missing -> {
                    // Gate network work; cached previews stay available during scrolling and rate limits.
                    snapshotFlow { loadRequested }.first { it }
                    if (detail) viewModel.detailPreview(icon, tint.toArgb())
                    else viewModel.preview(icon, tint.toArgb())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("OnlineIcons", "Could not load preview: ${icon.svgUrl}", error)
            null
        }
        value = preview?.let { OnlinePreviewLoadState.Ready(it) } ?: OnlinePreviewLoadState.Failed
    }
}

@Composable
private fun OnlinePreviewFailure(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CloudOff,
        contentDescription = stringResource(R.string.onlineIconLoadFailed),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
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
        val solid = with(SvgVectorImporter) { path.brush(tint) }
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
