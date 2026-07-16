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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.renkinProject.renkin.data.OnlineLibrariesKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.data.online.OnlineIconLibraries
import dev.renkinProject.renkin.data.online.OnlineIconRepository
import dev.renkinProject.renkin.vector.SvgVectorImporter
import kotlinx.coroutines.launch

/**
 * The vector editor's entry into the online libraries. Hidden entirely until the user opts
 * in via Settings ([OnlineLibrariesKey]) — the browser is the only feature besides the store
 * lookups that touches the network.
 */
@Composable
internal fun OnlineIconsButton(onPicked: (SvgVectorImporter.ImportedSvg, sourceUrl: String) -> Unit) {
    val enabled = getPreferences().getBooleanValue(OnlineLibrariesKey)
    if (!enabled) return
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(
        onClick = { open = true },
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.TravelExplore,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.onlineIconsButton),
            modifier = Modifier.padding(start = 6.dp)
        )
    }
    if (open) {
        OnlineIconBrowserDialog(
            onPicked = { imported, url ->
                open = false
                onPicked(imported, url)
            },
            onDismiss = { open = false }
        )
    }
}

/**
 * Fullscreen browser over the curated FOSS icon libraries (see [OnlineIconLibraries]): pick a
 * library, search its index, tap an icon. Only the index and the tapped/visible SVGs are
 * downloaded (cached on disk). [onPicked] receives the parsed SVG plus its public source URL,
 * which the caller stores as the icon's attribution reference.
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

    var library by remember { mutableStateOf(OnlineIconLibraries.first()) }
    var query by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    // null while (re)loading; the produceState is keyed on the library and the retry counter.
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
    var importing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = stringResource(R.string.onlineIconsTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (importing) {
                        LoadingIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OnlineIconLibraries.forEach { candidate ->
                        FilterChip(
                            selected = library == candidate,
                            onClick = { library = candidate },
                            label = { Text(candidate.label) }
                        )
                    }
                }
                // Licence + source, always visible: the whole point of the curated list is
                // that reuse is allowed — say under which terms, and link the project.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onlineIconsLicense, library.license),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkText(text = "GitHub", url = library.projectUrl)
                }

                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth()
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
                                OnlineIconTile(icon, repository, enabled = !importing) {
                                    if (importing) return@OnlineIconTile
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
                        }
                    }
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
