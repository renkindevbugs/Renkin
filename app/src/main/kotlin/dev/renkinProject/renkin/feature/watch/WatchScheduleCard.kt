@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.feature.watch

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.watch.WatchCheckOutcome
import dev.renkinProject.renkin.data.watch.WatchCheckStatus
import dev.renkinProject.renkin.data.watch.WatchReadIssue
import dev.renkinProject.renkin.data.watch.WatchReadFailureReason
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.ui.DialogBadge
import dev.renkinProject.renkin.ui.DialogCard
import dev.renkinProject.renkin.ui.DialogFactRow
import dev.renkinProject.renkin.ui.DialogPrimaryAction
import dev.renkinProject.renkin.ui.DialogTone
import dev.renkinProject.renkin.ui.MessageDialog
import dev.renkinProject.renkin.ui.PackIconImage
import dev.renkinProject.renkin.ui.getCurrentContext
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.IconShape
import androidx.compose.material.icons.filled.Layers
import dev.renkinProject.renkin.ui.theme.readableAddedTextColor
import java.util.Calendar

@Composable
internal fun WatchScheduleCard(
    automatic: Boolean,
    scheduleUpdating: Boolean,
    nextCheckAt: Long?,
    intervalMinutes: Int,
    lastCheck: WatchCheckStatus?,
    onAutomaticChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    iconPacks: List<IconPack> = emptyList()
) {
    val context = getCurrentContext()
    var showDetails by remember { mutableStateOf(false) }
    var showInterval by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val statusIcon = when (lastCheck?.outcome) {
        WatchCheckOutcome.COMPLETE -> Icons.Filled.Check
        WatchCheckOutcome.PARTIAL, WatchCheckOutcome.FAILED -> Icons.Filled.Warning
        null -> Icons.Filled.Info
    }
    val statusColor = when (lastCheck?.outcome) {
        WatchCheckOutcome.COMPLETE -> readableAddedTextColor(colors.surfaceContainerLow)
        WatchCheckOutcome.PARTIAL, WatchCheckOutcome.FAILED -> colors.error
        null -> colors.onSurfaceVariant
    }
    val nextLabel = when {
        !automatic -> stringResource(R.string.watchAutomaticChecksOff)
        nextCheckAt != null -> formatNextCheck(context, nextCheckAt)
        else -> stringResource(R.string.watchNextCheckUnknown)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = CardShape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = automatic, enabled = !scheduleUpdating, role = Role.Switch,
                        onValueChange = onAutomaticChange)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(if (automatic) R.string.watchAutomaticChecks else R.string.watchManualChecks),
                        style = MaterialTheme.typography.titleSmall)
                    Text(nextLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(checked = automatic, onCheckedChange = null, enabled = !scheduleUpdating)
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lastCheck?.let {
                            stringResource(R.string.watchLastCheckAt, formatCheckTime(context, it.checkedAt, shortToday = true))
                        } ?: stringResource(R.string.watchNeverChecked),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(onClick = { showDetails = true }) {
                        Icon(statusIcon,
                            contentDescription = stringResource(R.string.watchCheckDetailsAction, checkTitle(lastCheck)),
                            tint = statusColor, modifier = Modifier.size(20.dp))
                    }
                }
                TextButton(
                    onClick = { showInterval = true },
                    enabled = automatic && !scheduleUpdating,
                    modifier = Modifier.height(32.dp),
                    shape = SwatchShape,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = colors.surfaceContainerHighest)
                ) {
                    Text(intervalLabel(intervalMinutes), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (showDetails) WatchCheckDetailsDialog(lastCheck, iconPacks, onDismiss = { showDetails = false })
    if (showInterval) {
        WatchIntervalSheet(
            selectedMinutes = intervalMinutes,
            onSelect = { minutes -> onIntervalChange(minutes); showInterval = false },
            onDismiss = { showInterval = false }
        )
    }
}

@Composable
private fun checkTitle(status: WatchCheckStatus?): String = stringResource(when (status?.outcome) {
    WatchCheckOutcome.COMPLETE -> R.string.watchCheckCompleteTitle
    WatchCheckOutcome.PARTIAL -> R.string.watchCheckPartialTitle
    WatchCheckOutcome.FAILED -> R.string.watchCheckFailedTitle
    null -> R.string.watchNeverChecked
})

@Composable
private fun WatchCheckDetailsDialog(status: WatchCheckStatus?, iconPacks: List<IconPack>, onDismiss: () -> Unit) {
    val context = getCurrentContext()
    val body = when (status?.outcome) {
        WatchCheckOutcome.COMPLETE -> if (status.foundCount == 0) stringResource(R.string.watchLastCheckNoIcons)
            else pluralStringResource(R.plurals.watchLastCheckFound, status.foundCount, status.foundCount)
        WatchCheckOutcome.PARTIAL -> stringResource(R.string.watchPartialExplanation)
        WatchCheckOutcome.FAILED -> stringResource(R.string.watchLastCheckFailed)
        null -> stringResource(R.string.watchNoCheckDetails)
    }
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = {
            when (status?.outcome) {
                WatchCheckOutcome.COMPLETE -> DialogBadge(Icons.Filled.Check)
                WatchCheckOutcome.PARTIAL, WatchCheckOutcome.FAILED -> DialogBadge(Icons.Filled.Warning, DialogTone.Danger)
                null -> DialogBadge(Icons.Filled.Info, DialogTone.Neutral)
            }
        },
        title = checkTitle(status),
        body = AnnotatedString(body),
        actions = { DialogPrimaryAction(stringResource(R.string.close), onDismiss) }
    ) {
        if (status != null) {
            status.readIssues.groupBy { it.packPackage }.forEach { (packPackage, issues) ->
                DialogCard {
                    Text(stringResource(R.string.watchUnreadablePacksTitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WatchFailedPackHeading(packPackage, issues.first().packName)
                    issues.forEachIndexed { index, issue ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        WatchReadIssueDetail(issue)
                    }
                    Text(stringResource(R.string.watchPackReadRetryHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val legacyNames = status.failedPackNames.filterNot { name -> status.readIssues.any { it.packName == name } }
            if (legacyNames.isNotEmpty()) {
                DialogCard {
                    Text(stringResource(R.string.watchUnreadablePacksTitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    legacyNames.forEach { name ->
                        val pack = iconPacks.filter { it.applicationName == name }.singleOrNull()
                        WatchFailedPackHeading(pack?.packageName, name)
                    }
                    Text(stringResource(R.string.watchLegacyDetailsHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DialogCard {
                DialogFactRow(stringResource(R.string.watchDetailsLastCheck), formatCheckTime(context, status.checkedAt))
                if (status.lastSuccessfulAt > 0) {
                    DialogFactRow(stringResource(R.string.watchDetailsLastSuccess), formatCheckTime(context, status.lastSuccessfulAt))
                }
                DialogFactRow(stringResource(R.string.watchDetailsFound),
                    if (status.outcome == WatchCheckOutcome.FAILED) stringResource(R.string.watchDetailsUnknown)
                    else status.foundCount.toString())
            }
        }
    }
}

@Composable
private fun WatchFailedPackHeading(packPackage: String?, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (packPackage != null) PackIconImage(packPackage, 40.dp)
        else Surface(shape = IconShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Text(name, style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WatchReadIssueDetail(issue: WatchReadIssue) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (issue.appName ?: issue.appPackage)?.let { name ->
            Text(name, style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(when (issue.reason) {
            WatchReadFailureReason.PACK_UNREADABLE -> R.string.watchPackReadFailedReason
            WatchReadFailureReason.MISSING_DRAWABLE -> R.string.watchMissingDrawableReason
            WatchReadFailureReason.ICON_UNREADABLE -> R.string.watchIconReadFailedReason
        }), style = MaterialTheme.typography.bodyMedium)
        issue.drawableName?.let { name ->
            Text(stringResource(R.string.watchFailedDrawableResource, name),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WatchIntervalSheet(selectedMinutes: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = (listOf(60, 360, 720, 1440, selectedMinutes) +
        if (BuildConfig.DEBUG) listOf(15) else emptyList()).distinct().sorted()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.watchIntervalTitle), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp))
            Text(stringResource(R.string.watchIntervalSummary), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Column(Modifier.selectableGroup()) {
                options.forEach { minutes ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = minutes == selectedMinutes,
                            role = Role.RadioButton, onClick = { onSelect(minutes) })
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = minutes == selectedMinutes, onClick = null)
                        Text(
                            if (minutes % 60 == 0) pluralStringResource(R.plurals.watchEveryHours, minutes / 60, minutes / 60)
                            else pluralStringResource(R.plurals.watchEveryMinutes, minutes, minutes),
                            modifier = Modifier.weight(1f)
                        )
                        Text(intervalLabel(minutes), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String = if (minutes % 60 == 0)
    stringResource(R.string.watchIntervalHours, minutes / 60)
else stringResource(R.string.watchIntervalMinutes, minutes)

private fun formatCheckTime(context: Context, at: Long, shortToday: Boolean = false): String =
    DateUtils.formatDateTime(context, at, if (shortToday && DateUtils.isToday(at)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)

private fun formatNextCheck(context: Context, at: Long): String {
    val time = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)
    if (DateUtils.isToday(at)) return context.getString(R.string.watchCheckToday, time)
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val next = Calendar.getInstance().apply { timeInMillis = at }
    if (tomorrow.get(Calendar.ERA) == next.get(Calendar.ERA) && tomorrow.get(Calendar.YEAR) == next.get(Calendar.YEAR) &&
        tomorrow.get(Calendar.DAY_OF_YEAR) == next.get(Calendar.DAY_OF_YEAR)) {
        return context.getString(R.string.watchCheckTomorrow, time)
    }
    return formatCheckTime(context, at)
}
