@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.IconShape

@Composable
internal fun PackUsageContent(entries: List<ApplicationProvider.PackUsage>) {
    val total = entries.sumOf { it.count.toLong() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
        Surface(shape = CardShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.packUsageAttributedIcons), style = MaterialTheme.typography.labelLarge)
                Text(total.toString(), style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"))
                Text(
                    stringResource(R.string.packUsageSourceSummary,
                        entries.count { it.count > 0 }, entries.count { !it.installed && it.count > 0 }),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text(
            stringResource(if (total == 0L) R.string.packUsageNoAttributedIcons else R.string.packUsageShareDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        entries.forEach { entry -> PackUsageSourceRow(entry, total) }
    }
}

@Composable
private fun PackUsageSourceRow(entry: ApplicationProvider.PackUsage, total: Long) {
    val fraction = if (total > 0) entry.count.toFloat() / total else 0f
    val percent = kotlin.math.round(fraction * 100).toInt()
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entry.installed && !entry.packageName.startsWith("online:")) {
                    PackIconImage(entry.packageName, 40.dp)
                } else {
                    Surface(shape = IconShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(if (entry.packageName.startsWith("online:")) Icons.Filled.Public else Icons.Filled.Layers,
                                contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!entry.installed) {
                        Text(stringResource(R.string.packUsageNotInstalled), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.count.toString(), style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"))
                    Text(stringResource(R.string.packUsagePercent, percent), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).clearAndSetSemantics { }) {
                drawRect(track)
                if (fraction > 0f) drawRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
            }
        }
    }
}
