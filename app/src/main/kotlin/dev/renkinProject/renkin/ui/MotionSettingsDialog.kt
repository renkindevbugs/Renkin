@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.MotionPart
import dev.renkinProject.renkin.ui.theme.InnerShape

private val MotionPart.icon: ImageVector
    get() = when (this) {
        MotionPart.ICON_FLIGHT -> Icons.Filled.OpenInFull
        MotionPart.EDITOR_TABS -> Icons.Filled.Tab
        MotionPart.BUILD -> Icons.Filled.AutoAwesome
        MotionPart.ATTENTION -> Icons.Filled.NotificationsActive
    }

private val MotionPart.label: Int
    get() = when (this) {
        MotionPart.ICON_FLIGHT -> R.string.motionIconFlight
        MotionPart.EDITOR_TABS -> R.string.motionEditorTabs
        MotionPart.BUILD -> R.string.motionBuild
        MotionPart.ATTENTION -> R.string.motionAttention
    }

private val MotionPart.summary: Int
    get() = when (this) {
        MotionPart.ICON_FLIGHT -> R.string.motionIconFlightSummary
        MotionPart.EDITOR_TABS -> R.string.motionEditorTabsSummary
        MotionPart.BUILD -> R.string.motionBuildSummary
        MotionPart.ATTENTION -> R.string.motionAttentionSummary
    }

@Composable
internal fun motionSettingsSummary(reduced: Set<MotionPart>): String {
    val total = MotionPart.entries.size
    return when (reduced.size) {
        0 -> stringResource(R.string.motionAllOn)
        total -> stringResource(R.string.motionAllReduced)
        else -> stringResource(R.string.motionSomeOn, total - reduced.size, total)
    }
}

@Composable
internal fun MotionSettingsDialog(
    reduced: Set<MotionPart>,
    onReduce: (parts: Collection<MotionPart>, reduced: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val allReduced = reduced.size == MotionPart.entries.size
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Animation) },
        title = stringResource(R.string.motionSettingsTitle),
        body = AnnotatedString(stringResource(R.string.motionSettingsDescription)),
        actions = { DialogPrimaryAction(stringResource(R.string.close), onDismiss) }
    ) {
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MotionSwitchRow(
                icon = null,
                label = stringResource(R.string.motionReduceAll),
                summary = stringResource(R.string.motionReduceAllSummary),
                checked = allReduced,
                onCheckedChange = { onReduce(MotionPart.entries, it) }
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            MotionPart.entries.forEach { part ->
                MotionSwitchRow(
                    icon = part.icon,
                    label = stringResource(part.label),
                    summary = stringResource(part.summary),
                    checked = part !in reduced,
                    onCheckedChange = { enabled -> onReduce(listOf(part), !enabled) }
                )
            }
        }
    }
}

@Composable
private fun MotionSwitchRow(
    icon: ImageVector?,
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmallEmphasized)
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
