package dev.renkinProject.renkin.feature.backup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.ui.DialogBadge
import dev.renkinProject.renkin.ui.DialogCard
import dev.renkinProject.renkin.ui.DialogFactRow
import dev.renkinProject.renkin.ui.DialogPrimaryAction
import dev.renkinProject.renkin.ui.DialogSecondaryAction
import dev.renkinProject.renkin.ui.DialogTone
import dev.renkinProject.renkin.ui.MessageDialog
import dev.renkinProject.renkin.ui.boldStringResource
import dev.renkinProject.renkin.ui.theme.SwatchShape
import java.text.DateFormat
import java.util.Date

/** Replace-everything confirmation; profiles and watch rules expand so the user can check what is inside. */
@Composable
fun BackupRestorePreviewDialog(
    preview: BackupManager.BackupPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val backupDate = remember(preview.exportedAt) {
        preview.exportedAt.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
    }
    val origin = listOfNotNull(
        backupDate?.let { stringResource(R.string.backupPreviewFrom, it) },
        preview.appVersion.takeIf { it.isNotBlank() }?.let { stringResource(R.string.backupPreviewAppVersion, it) }
    ).joinToString(" · ").takeIf { it.isNotEmpty() }

    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Restore, DialogTone.Danger) },
        title = stringResource(R.string.importBackupTitle),
        supporting = origin,
        body = boldStringResource(R.string.importBackupText),
        actions = {
            DialogPrimaryAction(stringResource(R.string.backupRestoreAction), onConfirm, DialogTone.Danger)
            DialogSecondaryAction(stringResource(R.string.dismiss), onDismiss)
        }
    ) {
        DialogCard {
            ExpandableFact(stringResource(R.string.backupPreviewProfiles), preview.profiles.size.toString(),
                expandable = preview.profiles.isNotEmpty()) {
                preview.profiles.forEach { profile ->
                    DialogFactRow(
                        profile.name,
                        pluralStringResource(R.plurals.backupPreviewProfileIconCount, profile.iconCount, profile.iconCount)
                    )
                }
            }
            DialogFactRow(stringResource(R.string.backupPreviewIcons), preview.iconCount.toString())
            ExpandableFact(stringResource(R.string.backupPreviewWatchRules),
                (preview.activeWatchRules + preview.completedWatchRules).toString(),
                expandable = preview.activeWatchRules + preview.completedWatchRules > 0) {
                Text(
                    stringResource(R.string.backupPreviewWatchBreakdown, preview.activeWatchRules, preview.completedWatchRules),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DialogFactRow(stringResource(R.string.backupPreviewSavedStyles), preview.savedStyles.toString())
            DialogFactRow(stringResource(R.string.backupPreviewUploadedImages), preview.uploadedImages.toString())
        }
    }
}

@Composable
private fun ExpandableFact(label: String, value: String, expandable: Boolean, details: @Composable () -> Unit) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expandChevron")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(SwatchShape)
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            if (expandable) {
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp).rotate(rotation))
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp, start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                details()
            }
        }
    }
}
