@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.online.DiscoveredRepo
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.data.online.OnlineIconLibraries
import dev.renkinProject.renkin.data.online.OnlineIconLibrary
import dev.renkinProject.renkin.data.online.OnlineIconRepository
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.vector.SvgVectorImporter
import kotlinx.coroutines.launch

/**
 * Fullscreen browser over the curated FOSS icon libraries (see [OnlineIconLibraries]),
 * structured like the Create tab's pack browser: a library list first, then the tapped
 * library's searchable grid. Only the index and the visible/tapped SVGs are downloaded
 * (cached on disk). [onPicked] receives the parsed SVG plus its public source URL, which
 * the caller stores as the icon's attribution reference.
 */
@Composable
internal fun OnlineIconBrowserDialog(
    onPicked: (SvgVectorImporter.ImportedSvg, sourceUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = getCurrentContext()
    val repository = remember { OnlineIconRepository(context) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val importFailedMessage = stringResource(R.string.svgImportFailed)

    // null = the library list; non-null = that library's icon grid (back returns to the list).
    var library by remember { mutableStateOf<OnlineIconLibrary?>(null) }
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val back: () -> Unit = {
        if (library != null) {
            library = null
            query = ""
        } else {
            onDismiss()
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
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    // Inside a library the title names it — like a pack's own browser page.
                    Text(
                        text = library?.label ?: stringResource(R.string.onlineIconsTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (importing) {
                        LoadingIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }

                val current = library
                if (current == null) {
                    LibraryList(repository = repository, onOpen = { library = it })
                } else {
                    LibraryGrid(
                        library = current,
                        repository = repository,
                        query = query,
                        onQueryChange = { query = it },
                        enabled = !importing,
                        onPick = { icon ->
                            if (!importing) {
                                importing = true
                                scope.launch {
                                    val imported = repository.svg(icon)
                                        ?.let { SvgVectorImporter.parse(it) }
                                    importing = false
                                    if (imported == null) {
                                        toaster.show(importFailedMessage)
                                    } else {
                                        onPicked(imported, icon.svgUrl)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * The library picker: the curated sets on top, then an endless, scrollable community section
 * fed by GitHub's `icon-pack` topic (most-starred first, "Load more" appends pages). The
 * community rows show stars and the declared licence — or an explicit unknown-licence
 * warning, since anything on GitHub can appear there.
 */
@Composable
private fun LibraryList(repository: OnlineIconRepository, onOpen: (OnlineIconLibrary) -> Unit) {
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<List<DiscoveredRepo>>>(emptyList()) }
    var loadingPage by remember { mutableStateOf(false) }
    var discoverFailed by remember { mutableStateOf(false) }
    val loadNext: () -> Unit = {
        if (!loadingPage) {
            loadingPage = true
            discoverFailed = false
            scope.launch {
                val next = repository.discoverRepos(pages.size + 1)
                if (next == null) discoverFailed = true else pages = pages + listOf(next)
                loadingPage = false
            }
        }
    }
    LaunchedEffect(Unit) { if (pages.isEmpty()) loadNext() }
    // The curated sets also live in the topic — don't list them twice.
    val discovered = remember(pages) {
        pages.flatten().distinctBy { "${it.owner}/${it.repo}" }.filterNot { repo ->
            OnlineIconLibraries.any { it.owner == repo.owner && it.repo == repo.repo }
        }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .drawVerticalScrollbar(listState)
    ) {
        item(key = "curatedHeader") { ListSectionLabel(stringResource(R.string.onlineCuratedSection)) }
        items(OnlineIconLibraries, key = { it.id }) { library ->
            LibraryRow(
                title = library.label,
                subtitle = stringResource(R.string.onlineIconsLicense, library.license),
                subtitleTint = null,
                projectUrl = library.projectUrl
            ) { onOpen(library) }
        }

        item(key = "communityHeader") {
            Column {
                ListSectionLabel(stringResource(R.string.onlineCommunitySection))
                Text(
                    text = stringResource(R.string.onlineCommunityHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(discovered, key = { "${it.owner}/${it.repo}" }) { repo ->
            LibraryRow(
                title = "${repo.owner}/${repo.repo}",
                subtitle = "★ ${repo.stars} · " + (repo.license
                    ?: stringResource(R.string.onlineUnknownLicense)),
                subtitleTint = if (repo.license == null) MaterialTheme.colorScheme.error else null,
                projectUrl = "https://github.com/${repo.owner}/${repo.repo}",
                description = repo.description.takeIf { it.isNotBlank() }
            ) { onOpen(repo.toLibrary()) }
        }

        item(key = "communityFooter") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadingPage -> LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                    discoverFailed -> TextButton(onClick = loadNext) {
                        Text(stringResource(R.string.reload))
                    }
                    else -> TextButton(onClick = loadNext) {
                        Text(stringResource(R.string.onlineLoadMore))
                    }
                }
            }
        }
    }
}

/** Small section label splitting the curated sets from the community results. */
@Composable
private fun ListSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

/** One tappable library/repo row: name, licence/stars line, optional description, GitHub link. */
@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    subtitleTint: Color?,
    projectUrl: String,
    description: String? = null,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.TravelExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = subtitleTint ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkText(text = "GitHub", url = projectUrl)
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** One library's searchable icon grid, index loaded (and disk-cached) on entry. */
@Composable
private fun LibraryGrid(
    library: OnlineIconLibrary,
    repository: OnlineIconRepository,
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    onPick: (OnlineIcon) -> Unit
) {
    var retry by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val icons by produceState<List<OnlineIcon>?>(null, library, retry) {
        value = null
        loadFailed = false
        val loaded = repository.icons(library)
        loadFailed = loaded == null
        value = loaded
    }
    val filtered = remember(icons, query) {
        val needle = query.trim().lowercase().replace(' ', '-')
        val list = icons.orEmpty()
        if (needle.isEmpty()) list else list.filter { needle in it.slug }
    }

    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.searchIcons)
        )
    }
    HorizontalDivider()

    when {
        icons == null && !loadFailed -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
        }

        loadFailed -> EmptyState(
            icon = Icons.Filled.CloudOff,
            text = stringResource(R.string.onlineIconsLoadFailed),
            modifier = Modifier.fillMaxSize(),
            actionLabel = stringResource(R.string.reload),
            onAction = { retry++ }
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
                items(filtered, key = { "${it.library.id}/${it.slug}" }) { icon ->
                    OnlineIconTile(icon, repository, enabled = enabled) { onPick(icon) }
                }
            }
        }
    }
}

/** One grid tile: lazily fetches (disk-cached) and renders the icon's SVG, name below. */
@Composable
private fun OnlineIconTile(
    icon: OnlineIcon,
    repository: OnlineIconRepository,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val vector by produceState<ImageVector?>(null, icon) {
        value = repository.svg(icon)
            ?.let { SvgVectorImporter.parse(it) }
            ?.toPreviewVector(tint)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            val image = vector
            if (image != null) {
                Image(
                    painter = rememberVectorPainter(image),
                    contentDescription = icon.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = dev.renkinProject.renkin.ui.theme.InnerShape,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {}
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
    }
}

/**
 * A theme-tinted preview vector from an imported SVG — same path semantics as the vector
 * editor's import (fill vs stroke with authored widths), just coloured for the browser grid.
 */
private fun SvgVectorImporter.ImportedSvg.toPreviewVector(tint: Color): ImageVector? = runCatching {
    val builder = ImageVector.Builder(
        defaultWidth = 44.dp,
        defaultHeight = 44.dp,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
    for (path in paths) {
        val nodes = PathParser().parsePathString(path.pathData).toNodes()
        if (path.filled) {
            builder.addPath(nodes, fill = SolidColor(tint))
        } else {
            builder.addPath(
                nodes,
                stroke = SolidColor(tint),
                strokeLineWidth = path.strokeWidth ?: (viewportHeight / 24f)
            )
        }
    }
    builder.build()
}.getOrNull()
