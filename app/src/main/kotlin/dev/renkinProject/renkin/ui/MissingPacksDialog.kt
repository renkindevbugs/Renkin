package dev.renkinProject.renkin.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.IconLockManager.MissingPack
import dev.renkinProject.renkin.data.VERDICT_UNKNOWN
import dev.renkinProject.renkin.ui.theme.CardShape

/**
 * Lists the icon packs the active profile's locked icons come from: paid packs to install,
 * and packs still waiting for a price check. Each row links to the pack's Play Store page.
 * "Don't show again" silences only the automatic prompt for THIS profile — the top-bar
 * badge and banner keep showing while anything stays locked.
 */
@Composable
fun MissingPacksDialog(packs: List<MissingPack>, onDismiss: (dontShowAgain: Boolean) -> Unit) {
    val openLink = rememberLinkOpener()
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }

    MessageDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        badge = { DialogBadge(Icons.Filled.Lock, DialogTone.Neutral) },
        title = stringResource(R.string.missingPacksTitle),
        body = boldStringResource(R.string.missingPacksText),
        actions = { DialogPrimaryAction(stringResource(R.string.ok), { onDismiss(dontShowAgain) }) }
    ) {
        DialogCard {
            packs.forEach { pack ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Decisive verdict = the pack is installable somewhere, so the fix is to
                    // install it; UNKNOWN = still checking availability.
                    val pending = pack.verdict == VERDICT_UNKNOWN
                    Icon(
                        imageVector = if (pending) Icons.Filled.CloudOff else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(pack.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        DialogCardNote(stringResource(
                            if (pending) R.string.missingPackPending else R.string.missingPackInstall, pack.iconCount
                        ))
                    }
                    TextButton(onClick = {
                        openLink("https://play.google.com/store/apps/details?id=${pack.packageName}")
                    }) { Text(stringResource(R.string.missingPackOpenStore)) }
                }
            }
        }
        DontShowAgainRow(dontShowAgain, R.string.missingPackDontShowAgain) { dontShowAgain = it }
    }
}

@Composable
private fun DontShowAgainRow(checked: Boolean, @StringRes label: Int, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Shown before exporting a profile to share: its pack icons only work for recipients who have
 * the same packs installed. [onShare] carries the "don't show again" choice so the caller can
 * persist it. Informational (not destructive), so it uses the neutral confirm styling.
 */
@Composable
fun ProfileShareWarningDialog(onShare: (dontShowAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Share) },
        title = stringResource(R.string.shareWarningTitle),
        body = boldStringResource(R.string.shareWarningText),
        actions = {
            DialogPrimaryAction(stringResource(R.string.shareWarningShare), { onShare(dontShowAgain) })
            DialogSecondaryAction(stringResource(R.string.dismiss), onDismiss)
        }
    ) {
        DontShowAgainRow(dontShowAgain, R.string.shareWarningDontShowAgain) { dontShowAgain = it }
    }
}

enum class IconPackStudioWarningTarget { PROFILE, BACKUP }

/**
 * Shared portability warning for profile sharing and full backups. IPS exports reuse one
 * package identity, so Renkin deliberately explains the limitation without claiming it can
 * identify which individual export APK is required.
 */
@Composable
fun IconPackStudioExportWarningDialog(
    target: IconPackStudioWarningTarget,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = when (target) {
        IconPackStudioWarningTarget.PROFILE -> R.string.ipsExportProfileWarningText
        IconPackStudioWarningTarget.BACKUP -> R.string.ipsExportBackupWarningText
    }
    val action = when (target) {
        IconPackStudioWarningTarget.PROFILE -> R.string.continueAction
        IconPackStudioWarningTarget.BACKUP -> R.string.exportBackup
    }
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Warning, DialogTone.Neutral) },
        title = stringResource(R.string.ipsExportWarningTitle),
        body = boldStringResource(message),
        actions = {
            DialogPrimaryAction(stringResource(action), onContinue)
            DialogSecondaryAction(stringResource(R.string.dismiss), onDismiss)
        }
    )
}

/**
 * Home-screen banner shown while the active profile has icons locked behind missing packs.
 * Tapping opens [MissingPacksDialog] (regardless of the profile's "don't show again").
 */
@Composable
fun MissingPacksBanner(packCount: Int, iconCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.missingPacksBanner, packCount, iconCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
