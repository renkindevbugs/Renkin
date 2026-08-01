package dev.renkinProject.renkin.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.GradientFamily
import dev.renkinProject.renkin.data.GradientPreset
import dev.renkinProject.renkin.data.GradientPresets
import dev.renkinProject.renkin.data.filterGradientPresets
import dev.renkinProject.renkin.data.gradientStopCounts
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.evenGradientPositions
import dev.renkinProject.renkin.icon.creator.normalizeGradientAngle
import dev.renkinProject.renkin.ui.theme.InnerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled gradient library. It hands back a whole [ColorizerStyle] rather than a colour list
 * so the sheet applies a preset exactly like any other style — including the angle picked here.
 */
@Composable
internal fun GradientGalleryDialog(
    onUse: (ColorizerStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val presets by produceState(emptyList<GradientPreset>()) {
        // Reading and parsing the asset is I/O; the dialog opens empty for one frame instead of
        // blocking the animation.
        value = withContext(Dispatchers.IO) { GradientPresets.load(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<GradientFamily?>(null) }
    var stops by rememberSaveable { mutableStateOf<Int?>(null) }
    var detail by remember { mutableStateOf<GradientPreset?>(null) }

    val shown = remember(presets, query, family, stops) {
        filterGradientPresets(presets, query, family, stops)
    }
    val gridState = rememberLazyGridState()
    // A new filter shows a different list; staying scrolled where the old one was reads as empty.
    LaunchedEffect(query, family, stops) { gridState.scrollToItem(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dismiss)
                        )
                    }
                    Text(
                        text = stringResource(R.string.gradientGalleryTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = shown.size.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.gradientGallerySearch),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                GradientFilterChips(
                    family = family,
                    onFamilyChange = { family = it },
                    stops = stops,
                    onStopsChange = { stops = it },
                    availableStops = remember(presets) { gradientStopCounts(presets) }
                )
                // The collection is someone else's work under the MIT licence; the credit belongs
                // where the presets are used, not only buried in the about dialog.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.gradientGalleryCredit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkText(
                        text = stringResource(R.string.gradientGalleryCreditLink),
                        url = UI_GRADIENTS_URL
                    )
                }
                HorizontalDivider()
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.gradientGalleryEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(GalleryCardWidth),
                    state = gridState,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .drawVerticalScrollbar(gridState)
                ) {
                    items(shown, key = { it.name }) { preset ->
                        GradientPresetCard(
                            preset = preset,
                            onClick = { detail = preset }
                        )
                    }
                }
            }
        }
    }

    detail?.let { preset ->
        GradientPresetDetailDialog(
            preset = preset,
            onUse = {
                onUse(it)
                detail = null
                onDismiss()
            },
            onDismiss = { detail = null }
        )
    }
}

/** Look on one scrolling row, stop count on the next — two questions, never mixed into one row. */
@Composable
private fun GradientFilterChips(
    family: GradientFamily?,
    onFamilyChange: (GradientFamily?) -> Unit,
    stops: Int?,
    onStopsChange: (Int?) -> Unit,
    availableStops: List<Int>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = family == null,
                onClick = { onFamilyChange(null) },
                label = { Text(stringResource(R.string.gradientFamilyAll)) }
            )
            GradientFamily.entries.forEach { entry ->
                FilterChip(
                    selected = family == entry,
                    onClick = { onFamilyChange(if (family == entry) null else entry) },
                    label = { Text(stringResource(entry.labelRes())) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.gradientStopFilterLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            FilterChip(
                selected = stops == null,
                onClick = { onStopsChange(null) },
                label = { Text(stringResource(R.string.gradientFamilyAll)) }
            )
            availableStops.forEach { count ->
                FilterChip(
                    selected = stops == count,
                    onClick = { onStopsChange(if (stops == count) null else count) },
                    label = { Text(count.toString()) }
                )
            }
        }
    }
}

private fun GradientFamily.labelRes(): Int = when (this) {
    GradientFamily.WARM -> R.string.gradientFamilyWarm
    GradientFamily.COOL -> R.string.gradientFamilyCool
    GradientFamily.PASTEL -> R.string.gradientFamilyPastel
    GradientFamily.DARK -> R.string.gradientFamilyDark
    GradientFamily.MONO -> R.string.gradientFamilyMono
}

/** Grid card: the gradient, its name, and a one-tap save into the user's own colours. */
@Composable
private fun GradientPresetCard(
    preset: GradientPreset,
    onClick: () -> Unit
) {
    val style = preset.asStyle()

    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GalleryCardPreviewHeight)
                    .colorizerSwatch(style)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 2.dp)
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SavedColorToggle(name = preset.name, style = style)
            }
        }
    }
}

/**
 * A preset seen big enough to judge, with the angle dial the sheet has — comparing two gradients
 * at thumbnail size is guesswork, and a direction that suits the icon is half the choice.
 */
@Composable
private fun GradientPresetDetailDialog(
    preset: GradientPreset,
    onUse: (ColorizerStyle) -> Unit,
    onDismiss: () -> Unit
) {
    var angle by rememberSaveable { mutableStateOf(GALLERY_ANGLE) }
    val style = preset.asStyle(angle)

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SavedColorToggle(name = preset.name, style = style)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DetailPreviewHeight)
                        .clip(InnerShape)
                        .colorizerSwatch(style)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AngleDial(
                        angle = angle,
                        contentDescription = stringResource(R.string.gradientAngle),
                        onAngleChange = { angle = normalizeGradientAngle(it) }
                    )
                    Text(
                        text = stringResource(
                            R.string.gradientGalleryStops,
                            preset.colors.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onUse(style) }) {
                Text(stringResource(R.string.gradientGalleryUse))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.colorizeCancel)) }
        }
    )
}

/**
 * Presets are plain colour lists: they spread evenly, and they are linear because that is what a
 * two-dimensional swatch in a library means. Both stay editable the moment they land in the sheet.
 */
private fun GradientPreset.asStyle(angle: Float = GALLERY_ANGLE) = ColorizerStyle(
    mode = ColorizerMode.GRADIENT,
    gradientType = GradientType.LINEAR,
    firstColor = colors.first(),
    gradientStops = colors.drop(1),
    gradientPositions = evenGradientPositions(colors.size),
    gradientAngle = angle
)

// Left to right, like every gradient library on the web: the tiles are wider than they are tall,
// and a top-down sweep leaves almost none of the middle colours visible.
private const val GALLERY_ANGLE = 90f

private const val UI_GRADIENTS_URL = "https://github.com/Ghosh/uiGradients"

private val GalleryCardWidth = 150.dp
private val GalleryCardPreviewHeight = 72.dp
private val DetailPreviewHeight = 160.dp
