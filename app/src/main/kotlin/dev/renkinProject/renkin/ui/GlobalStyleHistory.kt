package dev.renkinProject.renkin.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import dev.renkinProject.renkin.ui.theme.ChangedOrange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.GlobalOptionsViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.GLOBAL_STYLE_HISTORY_LIMIT
import dev.renkinProject.renkin.data.GlobalStyleHistoryItem
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.ui.modifier.shapeLabel
import dev.renkinProject.renkin.ui.modifier.matchingPreset
import dev.renkinProject.renkin.ui.modifier.shadowUiPresets
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import kotlin.math.roundToInt

private val HistoryPreviewSize = 24.dp
private const val HISTORY_PREVIEW_APPS = 3

/** Opens [GlobalStyleHistorySheet]. */
@Composable
internal fun GlobalHistoryButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.History, stringResource(R.string.globalHistoryTitle))
    }
}

/**
 * Every Apply of this profile, newest first. Tapping a row stages its recipe in the controls
 * (the screen's Apply writes it); [current] marks the row that matches what is applied now.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GlobalStyleHistorySheet(
    history: List<GlobalStyleHistoryItem>,
    current: GlobalModifierSnapshot?,
    previewApps: List<PackageInfoStruct>,
    viewModel: GlobalOptionsViewModel,
    onLoad: (GlobalStyleHistoryItem) -> Unit,
    onLoadNoStyle: () -> Unit,
    onDelete: (GlobalStyleHistoryItem) -> Unit,
    canBake: Boolean,
    onBake: () -> Unit,
    onUndoBake: (GlobalStyleHistoryItem) -> Unit,
    onDismiss: () -> Unit
) {
    val looks = remember(history) {
        history.map { item -> item to GlobalModifierState().apply { seedFrom(item.recipe) } }
    }
    val bakedLooks = remember(history) {
        history.mapNotNull { item -> item.bakedRecipe?.let { item.id to GlobalModifierState().apply { seedFrom(it) } } }.toMap()
    }
    val newestBakeIndex = history.indexOfFirst { it.isBake }
    val apps = remember(previewApps) { previewApps.take(HISTORY_PREVIEW_APPS) }
    // Only the newest entry can be what is applied now; an older identical look is just history.
    val currentId = looks.firstOrNull()?.takeIf { (_, look) -> current != null && look.snapshot() == current }?.first?.id
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(stringResource(R.string.globalHistoryTitle), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.globalHistoryHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                looks.forEachIndexed { index, (item, look) ->
                    if (newestBakeIndex >= 0 && index == newestBakeIndex + 1) item(key = "beforeBake") {
                        Text(
                            text = stringResource(R.string.globalHistoryBeforeBake),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    item(key = item.id) {
                        val isCurrent = item.id == currentId
                        val bakedLook = bakedLooks[item.id]
                        if (bakedLook != null) {
                            // The baked icons already carry this look, so the preview shows them as they are.
                            HistoryRow(
                                title = stringResource(R.string.globalHistoryBaked, formatHistoryTime(item.appliedAt)),
                                chips = historyChips(bakedLook),
                                options = null,
                                apps = apps,
                                viewModel = viewModel,
                                highlighted = isCurrent,
                                onClick = { onUndoBake(item) },
                                onDelete = null,
                                leading = Icons.Filled.LocalFireDepartment
                            )
                        } else {
                            HistoryRow(
                                title = if (isCurrent) {
                                    stringResource(R.string.globalHistoryCurrent, formatHistoryTime(item.appliedAt))
                                } else formatHistoryTime(item.appliedAt),
                                chips = historyChips(look),
                                options = if (look.hasAnyEffect) look.toModifierOptions() else null,
                                apps = apps,
                                viewModel = viewModel,
                                highlighted = isCurrent,
                                onClick = if (isCurrent) null else { { onLoad(item) } },
                                onDelete = if (isCurrent) null else { { onDelete(item) } },
                                bake = if (isCurrent && look.hasAnyEffect) {
                                    { BakeButton(enabled = canBake, onClick = onBake) }
                                } else null
                            )
                        }
                    }
                }
                item(key = "noStyle") {
                    HistoryRow(
                        title = stringResource(R.string.globalHistoryNoStyle),
                        chips = listOf(stringResource(R.string.globalHistoryNoStyleHint)),
                        options = null,
                        apps = apps,
                        viewModel = viewModel,
                        highlighted = false,
                        onClick = onLoadNoStyle,
                        onDelete = null
                    )
                }
                item(key = "limit") {
                    Text(
                        text = if (history.isEmpty()) stringResource(R.string.globalHistoryEmpty)
                        else pluralStringResource(R.plurals.globalHistoryLimit, GLOBAL_STYLE_HISTORY_LIMIT, GLOBAL_STYLE_HISTORY_LIMIT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    title: String,
    chips: List<String>,
    options: GenerationOptions?,
    apps: List<PackageInfoStruct>,
    viewModel: GlobalOptionsViewModel,
    highlighted: Boolean,
    onClick: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    leading: ImageVector? = null,
    bake: (@Composable () -> Unit)? = null
) {
    val container = if (highlighted) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = InnerShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(InnerShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = if (bake != null) 0.dp else 10.dp, end = 4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                apps.forEach { app -> HistoryIconPreview(app, options, viewModel) }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    leading?.let { Icon(it, contentDescription = null, tint = BakeFlame, modifier = Modifier.size(16.dp)) }
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    chips.forEach { chip ->
                        Surface(shape = SwatchShape, color = container, tonalElevation = 2.dp) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        stringResource(R.string.globalHistoryDelete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        bake?.let {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            ) { it() }
        }
        }
    }
}

// The flame marks a bake wherever it appears; tinted apart from the accent so it reads as an event.
private val BakeFlame = ChangedOrange

@Composable
private fun BakeButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            stringResource(if (enabled) R.string.globalBakeAction else R.string.globalBakeApplyFirst),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Confirms a bake: the icons keep their look, and it becomes their new original. */
@Composable
internal fun GlobalStyleBakeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.LocalFireDepartment) },
        title = stringResource(R.string.globalBakeTitle),
        body = AnnotatedString(stringResource(R.string.globalBakeText)),
        actions = {
            DialogPrimaryAction(stringResource(R.string.globalBakeConfirm), onConfirm)
            DialogSecondaryAction(stringResource(R.string.colorizeCancel), onDismiss)
        }
    )
}

/** Confirms undoing [item]: the originals it replaced come back with the look it baked. */
@Composable
internal fun GlobalStyleUndoBakeDialog(item: GlobalStyleHistoryItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.AutoMirrored.Filled.Undo) },
        title = stringResource(R.string.globalUndoBakeTitle),
        supporting = formatHistoryTime(item.appliedAt),
        body = AnnotatedString(stringResource(R.string.globalUndoBakeText)),
        actions = {
            DialogPrimaryAction(stringResource(R.string.globalUndoBakeConfirm), onConfirm)
            DialogSecondaryAction(stringResource(R.string.colorizeCancel), onDismiss)
        }
    )
}

/** One app rendered with [options] from its base, or the base itself for "no effects". */
@Composable
private fun HistoryIconPreview(app: PackageInfoStruct, options: GenerationOptions?, viewModel: GlobalOptionsViewModel) {
    val base = app.baseIcon ?: app.createdIcon
    val targetPx = with(LocalDensity.current) { HistoryPreviewSize.roundToPx() }
    val baseBitmap = rememberIconBitmap(app, base, HistoryPreviewSize)
    val preview by produceState(
        options?.let { viewModel.cachedModifiedPreview(app, it, targetPx) },
        app.key, app.internalVersion, options, targetPx
    ) {
        if (value == null && base != null && options != null) value = viewModel.modifiedPreview(app, options, targetPx)
    }
    val painter = preview?.let { BitmapPainter(it.asImageBitmap()) }
        ?: if (options == null) baseBitmap?.let(::BitmapPainter) else null
    if (painter != null) {
        Image(painter, contentDescription = null, modifier = Modifier.size(HistoryPreviewSize))
    } else {
        Icon(
            Icons.Filled.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(HistoryPreviewSize)
        )
    }
}

/** The effects of a stored Global style [recipe], as the history rows name them. */
@Composable
internal fun globalStyleChips(recipe: Preferences): List<String> =
    historyChips(remember(recipe) { GlobalModifierState().apply { seedFrom(recipe) } })

@Composable
private fun historyChips(look: GlobalModifierState): List<String> = buildList {
    if (look.shape.isEnabled) {
        val summary = stringResource(R.string.globalShapeSummary, shapeLabel(look.shape.shape), (look.shape.scale * 100).roundToInt())
        val placement = stringResource(if (look.shape.crop) R.string.shapeCrop else R.string.shapePlate)
        add(stringResource(R.string.globalHistoryChip, stringResource(R.string.iconShapeTitle), "$summary · $placement"))
    }
    if (!look.modifiers.transform.isIdentity) add(stringResource(
        R.string.globalHistoryChip, stringResource(R.string.iconScale), formatPercent(look.modifiers.transform.scale)
    ))
    if (look.outline.isEnabled) add(stringResource(
        R.string.globalHistoryChip,
        stringResource(R.string.outlineTitle),
        stringResource(R.string.globalOutlineSummary, look.outline.width.roundToInt())
    ))
    if (look.shadow.enabled) add(stringResource(
        R.string.globalHistoryChip,
        stringResource(R.string.shadowTitle),
        look.shadow.matchingPreset(shadowUiPresets())?.label ?: stringResource(
            R.string.shadowCardSummary, look.shadow.blur.roundToInt(), (look.shadow.opacity * 100).roundToInt()
        )
    ))
    if (look.modifiers.effect.imageEdit == ImageEdit.COLORIZE) add(stringResource(
        R.string.globalHistoryChip,
        stringResource(R.string.colorize),
        stringResource(
            if (look.modifiers.effect.colorizer.mode == ColorizerMode.GRADIENT) R.string.colorizerGradient
            else R.string.globalOneColor
        )
    ))
    if (isEmpty()) add(stringResource(R.string.globalHistoryNoEffects))
}

@Composable
private fun formatHistoryTime(appliedAt: Long): String = DateUtils.formatDateTime(
    LocalContext.current, appliedAt,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
)

/** Says which history entry the controls show; Undo returns to the draft from before it. */
@Composable
internal fun GlobalHistoryLoadedBanner(load: GlobalHistoryLoad, onUndo: () -> Unit) {
    val text = if (load.appliedAt == GlobalHistoryLoad.NO_GLOBAL_STYLE) {
        stringResource(R.string.globalHistoryLoadedNoStyle)
    } else stringResource(R.string.globalHistoryLoaded, formatHistoryTime(load.appliedAt))
    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
        ) {
            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onUndo) { Text(stringResource(R.string.undoAction)) }
        }
    }
}
