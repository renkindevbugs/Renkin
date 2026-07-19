@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.ui.theme.GoldBase
import dev.renkinProject.renkin.ui.theme.GoldShimmer
import dev.renkinProject.renkin.ui.theme.CardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen's hero card: the primary icon source, front and centre, so a first-time user
 * sees "pick an icon pack" as step one instead of digging through the advanced options. Shows the
 * chosen pack (or a choose-your-pack call to action), the completion progress with the built /
 * added / removed diff bar, and opens the pack picker sheet on tap. Picking writes the same
 * primary source/pack preferences the options card used to own, then auto-refreshes the icons —
 * hand-picked and already-built icons survive that refresh (see PackageInfoStruct.isRefreshMade).
 */
@Composable
fun HeroPackCard(iconPacks: List<IconPack>) {
    val viewModel: MainViewModel = hiltViewModel()
    val prefs = getPreferences()
    val preferences = prefs.getPreferencesValue()
    val scope = rememberCoroutineScope()

    val source = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
    val packName = preferences.getStringValue(PrimaryIconPackKey)
    val selectedPack = iconPacks.find { it.packageName == packName }

    // Completion progress (moved here from the options card): blue = already built, green =
    // added since (pending build), red = removed since.
    val apps = viewModel.applicationList
    val builtKeys = viewModel.builtKeys
    val builtCount = apps.count { it.createdIcon != null && it.key in builtKeys }
    val addedCount = apps.count { it.createdIcon != null && it.key !in builtKeys }
    val removedCount = apps.count { it.createdIcon == null && it.key in builtKeys }
    val themedCount = builtCount + addedCount
    val totalCount = apps.size
    val fallbackCount = apps.count { it.createdIcon != null && it.isFallback }

    var sheetOpen by remember { mutableStateOf(false) }

    // Saved-but-not-built marker for the active profile (set by the save-before-switch flow).
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    val activeProfile = profiles.find { it.id == viewModel.activeProfileId }

    Surface(
        onClick = { sheetOpen = true },
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.heroPackLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (source == Source.ICON_PACK) {
                    val packIcon = rememberPackIcon(selectedPack?.packageName)
                    if (packIcon != null) {
                        Image(
                            bitmap = packIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(40.dp)
                                .clip(InnerShape)
                        )
                    }
                }
                Text(
                    text = when {
                        source == Source.ICON_PACK && selectedPack != null -> selectedPack.applicationName
                        source == Source.APPLICATION_ICON -> stringResource(R.string.sourceOwnIcons)
                        source == Source.APPLICATION_NAME -> stringResource(R.string.sourceTextIcons)
                        else -> stringResource(R.string.chooseYourPack)
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // The progress reflects the stored icons, which exist independently of the current
            // source pick — so it stays visible even with None selected.
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.completionProgress, themedCount, totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f)
                    )
                    // Pending changes since the last build, mirroring the bar colours
                    if (addedCount > 0) {
                        Text(
                            text = "+$addedCount",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AddedGreen
                        )
                    }
                    if (removedCount > 0) {
                        Text(
                            text = " −$removedCount",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChangeBar(totalCount, builtCount, addedCount, removedCount)
                // Fallback icons look themed but weren't a real pack match — call out the
                // count so a full bar isn't mistaken for "every app was found".
                if (fallbackCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.fallbackCount, fallbackCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
                if (activeProfile?.hasUnbuiltChanges == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.unbuiltChanges),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        PackPickerSheet(
            iconPacks = iconPacks,
            selectedSource = source,
            selectedPackage = packName,
            onDismiss = { sheetOpen = false },
            onPick = { newSource, newPackage ->
                sheetOpen = false
                scope.launch {
                    prefs.setEnumValue(PrimarySourceKey, newSource)
                    if (newPackage != null) prefs.setStringValue(PrimaryIconPackKey, newPackage)
                    if (newSource == Source.NONE) {
                        // No source: the unsaved refresh output goes away; locked icons stay.
                        viewModel.clearRefreshedIcons()
                    } else {
                        // Auto-refresh with the just-written preferences, so the pick takes
                        // effect without knowing about the refresh button. Hand-picked and
                        // built icons are safe.
                        viewModel.refresh()
                    }
                }
            }
        )
    }
}

/** The pack picker: installed icon packs (with usage counts), plus the non-pack sources. */
@Composable
private fun PackPickerSheet(
    iconPacks: List<IconPack>,
    selectedSource: Source,
    selectedPackage: String,
    onDismiss: () -> Unit,
    onPick: (source: Source, packPackage: String?) -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    val usage = remember { viewModel.packUsageCounts() }

    // Two anchors only (open / dismissed), like Mihon's AdaptiveSheet: with the default
    // partially-expanded middle state, the list's nested scroll hands off to a sheet drag at
    // both edges and stutters between anchors. Skipping it, the sheet opens full-height and
    // an edge over-drag goes straight into the single dismiss transition.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A fling that ends at the list edge leaves residual velocity, which the sheet would
    // consume as a drag — a visible twitch toward dismiss and back. Swallow fling leftovers
    // here; finger drags (UserInput) still pass through so drag-to-dismiss keeps working.
    // Workaround for https://issuetracker.google.com/issues/486562294 — delete once material3
    // ships the fix.
    val swallowFlingLeftovers = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.SideEffect) available else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // LazyColumn (not Column+verticalScroll): with many packs the list is taller than the
        // sheet, and the lazy list's nested-scroll handoff to the sheet is smooth at the edges.
        LazyColumn(
            modifier = Modifier.nestedScroll(swallowFlingLeftovers),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.choosePackTitle),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
            items(iconPacks, key = { it.packageName }) { pack ->
                val selected = selectedSource == Source.ICON_PACK && pack.packageName == selectedPackage
                PickerRow(
                    title = pack.applicationName,
                    subtitle = (usage[pack.packageName] ?: 0).let { count ->
                        if (count > 0) stringResource(R.string.packUsedCount, count) else null
                    },
                    icon = rememberPackIcon(pack.packageName),
                    selected = selected
                ) { onPick(Source.ICON_PACK, pack.packageName) }
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            item {
                // None: no source at all — picking it also discards the unsaved refresh output.
                PickerRow(
                    title = stringResource(R.string.none),
                    subtitle = null,
                    icon = null,
                    selected = selectedSource == Source.NONE
                ) { onPick(Source.NONE, null) }
            }
            item {
                PickerRow(
                    title = stringResource(R.string.sourceOwnIcons),
                    subtitle = null,
                    icon = null,
                    selected = selectedSource == Source.APPLICATION_ICON
                ) { onPick(Source.APPLICATION_ICON, null) }
            }
            item {
                PickerRow(
                    title = stringResource(R.string.sourceTextIcons),
                    subtitle = null,
                    icon = null,
                    selected = selectedSource == Source.APPLICATION_NAME
                ) { onPick(Source.APPLICATION_NAME, null) }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Loads a pack's launcher icon off the main thread; null while loading or when unavailable. */
@Composable
private fun rememberPackIcon(packageName: String?): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            packageName?.let { pkg ->
                runCatching {
                    context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()
            }
        }
    }.value
}

/**
 * Segmented completion bar: blue = icons already in the last built pack, green = added
 * since (pending build), red = removed since. Material 3 has no multi-colour progress
 * bar, so this hand-draws one with the stock indicator's modern traits: rounded capsule
 * ends, the gap before the remainder track, and the stop-indicator dot at the far end.
 */
@Composable
internal fun ChangeBar(total: Int, built: Int, added: Int, removed: Int) {
    val builtF by animateFloatAsState(if (total > 0) built / total.toFloat() else 0f, label = "builtFrac")
    val addedF by animateFloatAsState(if (total > 0) added / total.toFloat() else 0f, label = "addedFrac")
    val removedF by animateFloatAsState(if (total > 0) removed / total.toFloat() else 0f, label = "removedFrac")
    val primary = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    // Fully themed AND fully persisted (no pending +added/-removed): the bar celebrates in
    // gold with a soft highlight sweeping left to right. Any pending change keeps the sober
    // segmented palette — green/red still means "there is something to build".
    val complete = total > 0 && built == total && added == 0 && removed == 0
    val shimmerProgress = if (complete) {
        rememberInfiniteTransition(label = "goldShimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
            label = "shimmerSweep"
        ).value
    } else 0f

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val radius = size.height / 2f
        val progressEnd = size.width * (builtF + addedF + removedF).coerceAtMost(1f)

        // The coloured segments share one rounded-capsule clip, so the bar's outer ends are
        // round while the internal colour joins stay flush.
        if (progressEnd > 0f) {
            val capsule = Path().apply {
                addRoundRect(RoundRect(0f, 0f, progressEnd, size.height, CornerRadius(radius)))
            }
            clipPath(capsule) {
                if (complete) {
                    drawRect(color = GoldBase)
                    // The moving band travels a bit past both edges so the sweep fades in/out.
                    val band = size.width * 0.22f
                    val x = shimmerProgress * (size.width + 2f * band) - band
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                GoldShimmer.copy(alpha = 0.85f),
                                Color.Transparent
                            ),
                            startX = x - band,
                            endX = x + band
                        )
                    )
                } else {
                    val stops = listOf(
                        Triple(0f, builtF, primary),
                        Triple(builtF, builtF + addedF, AddedGreen),
                        Triple(builtF + addedF, builtF + addedF + removedF, errorColor)
                    )
                    for ((from, to, color) in stops) {
                        if (to > from) {
                            drawRect(
                                color = color,
                                topLeft = Offset(size.width * from, 0f),
                                size = Size(size.width * (to - from), size.height)
                            )
                        }
                    }
                }
            }
        }

        // Remainder track after the M3 progress gap, with the stop-indicator dot at its end.
        val gap = 4.dp.toPx()
        val trackStart = if (progressEnd > 0f) progressEnd + gap else 0f
        if (trackStart < size.width) {
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(trackStart, 0f),
                size = Size(size.width - trackStart, size.height),
                cornerRadius = CornerRadius(radius)
            )
        }
        drawCircle(
            color = if (complete) GoldBase else primary,
            radius = 2.dp.toPx(),
            center = Offset(size.width - radius, size.height / 2f)
        )
    }
}
