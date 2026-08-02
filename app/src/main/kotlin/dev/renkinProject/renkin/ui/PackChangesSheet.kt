@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.PackChange
import dev.renkinProject.renkin.apk.PackChangeKind
import dev.renkinProject.renkin.apk.PackChangeReason
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.ui.theme.IconShape
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.RemovedRed

/**
 * What a build would change, compared with the icons the installed pack already has. The badge
 * that opens it only says that something changed; this says what, and lets each app be opened
 * straight from the list.
 */
@Composable
internal fun PackChangesSheet(
    iconPacks: List<IconPack>,
    themed: Boolean,
    onBuild: () -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    val changes = viewModel.pendingPackChanges
    var filter by rememberSaveable { mutableStateOf<PackChangeKind?>(null) }
    val shown = remember(changes, filter) { changes.filter { filter == null || it.kind == filter } }
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<PackChange?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.packChangesTitle),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text(stringResource(R.string.packChangesAll, changes.size)) }
                )
                PackChangeKind.entries.forEach { kind ->
                    val count = changes.count { it.kind == kind }
                    // A chip that leads to an empty list is just a dead end.
                    if (count == 0) return@forEach
                    FilterChip(
                        selected = filter == kind,
                        onClick = { filter = if (filter == kind) null else kind },
                        label = { Text(stringResource(kind.labelRes(), count)) }
                    )
                }
            }

            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.packChangesEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .drawVerticalScrollbar(listState),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.application.key }) { change ->
                    PackChangeRow(change) { editing = change }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                Button(
                    onClick = {
                        onDismiss()
                        onBuild()
                    },
                    enabled = changes.isNotEmpty()
                ) {
                    Icon(imageVector = Icons.Filled.Build, contentDescription = null)
                    Text(
                        text = stringResource(R.string.packChangesBuild),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    editing?.let { change ->
        OpenAppOptions(iconPacks, change.application, themed) { editing = null }
    }
}

@Composable
private fun PackChangeRow(change: PackChange, onClick: () -> Unit) {
    val app = change.application
    // Removed rows have no icon of their own left, so they show what the launcher will go back to.
    val created = rememberCreatedIconBitmap(app, IconSize)

    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (created != null) {
                Image(
                    painter = BitmapPainter(created),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize).clip(IconShape)
                )
            } else {
                AppIcon(app, IconSize)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(change.reason.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = change.kind.tint(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PackChangeKind.tint() = when (this) {
    PackChangeKind.ADDED -> MaterialTheme.colorScheme.primary
    PackChangeKind.CHANGED -> MaterialTheme.colorScheme.tertiary
    // Same fixed red as the hero bar's removed segment, so the list and the bar agree.
    PackChangeKind.REMOVED -> RemovedRed
}

private fun PackChangeKind.labelRes(): Int = when (this) {
    PackChangeKind.ADDED -> R.string.packChangesAdded
    PackChangeKind.CHANGED -> R.string.packChangesChanged
    PackChangeKind.REMOVED -> R.string.packChangesRemoved
}

private fun PackChangeReason.labelRes(): Int = when (this) {
    PackChangeReason.REFRESH -> R.string.packChangesReasonRefresh
    PackChangeReason.HAND_EDIT -> R.string.packChangesReasonEdit
    PackChangeReason.ICON_REMOVED -> R.string.packChangesReasonRemoved
}

private val IconSize = 40.dp
