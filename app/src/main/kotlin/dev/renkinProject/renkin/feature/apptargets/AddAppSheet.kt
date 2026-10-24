@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.renkinProject.renkin.feature.apptargets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.PackAppCandidate
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.AppTarget
import dev.renkinProject.renkin.data.AppTargetCodec
import dev.renkinProject.renkin.extension.prettyDrawableName
import dev.renkinProject.renkin.ui.DialogBadge
import dev.renkinProject.renkin.ui.DialogCard
import dev.renkinProject.renkin.ui.DialogPrimaryAction
import dev.renkinProject.renkin.ui.DialogProgressBadge
import dev.renkinProject.renkin.ui.DialogSecondaryAction
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.MessageDialog
import dev.renkinProject.renkin.ui.SearchField
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.ui.theme.IconShape
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.SwatchShape

/** A candidate the user picked, waiting for the confirm dialog. */
private data class PendingTarget(val target: AppTarget, val disabled: Boolean)

/**
 * Adds an app the launcher list does not show: one search over this phone's disabled apps and
 * the components installed icon packs theme, with typing the component as the last resort.
 * [onAdded] receives the new row's key and whether its editor should open.
 */
@Composable
fun AddAppSheet(
    initialQuery: String,
    sourceSession: ProfileSessionToken,
    listedKeys: Set<String>,
    onDismiss: () -> Unit,
    onAdded: (key: String, openEditor: Boolean) -> Unit
) {
    val viewModel: AddAppViewModel = hiltViewModel()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var manualEntry by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingTarget?>(null) }

    LaunchedEffect(Unit) { viewModel.loadCandidates() }
    DisposableEffect(Unit) { onDispose { viewModel.releaseCandidates() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        if (manualEntry) {
            BackHandler { manualEntry = false }
            ManualComponentForm(
                initialPackage = query.trim().takeIf { AppTargetCodec.isValidPackageName(it) }.orEmpty(),
                onBack = { manualEntry = false },
                onContinue = { pending = PendingTarget(it, disabled = false) }
            )
        } else {
            CandidateList(
                viewModel = viewModel,
                query = query,
                onQueryChange = { query = it },
                listedKeys = listedKeys,
                onPick = { pending = it },
                onEnterComponent = { manualEntry = true }
            )
        }
    }

    pending?.let { picked ->
        AddAppConfirmDialog(
            pending = picked,
            busy = viewModel.busy,
            onDismiss = { pending = null },
            onAdd = { target, openEditor ->
                viewModel.add(sourceSession, target) { outcome ->
                    when (outcome) {
                        AddAppOutcome.ADDED -> {
                            toaster.show(context.getString(R.string.addAppAddedToast, target.label))
                            pending = null
                            onAdded(target.key, openEditor)
                        }
                        AddAppOutcome.ALREADY_LISTED -> {
                            toaster.show(context.getString(R.string.addAppAlreadyListed))
                            pending = null
                        }
                        AddAppOutcome.LIMIT_REACHED -> toaster.show(context.getString(R.string.addAppLimitReached))
                        AddAppOutcome.SESSION_CHANGED -> {
                            toaster.show(context.getString(R.string.profileSessionChanged))
                            onDismiss()
                        }
                        AddAppOutcome.FAILED -> toaster.show(context.getString(R.string.addAppFailed))
                    }
                }
            }
        )
    }
}

@Composable
private fun CandidateList(
    viewModel: AddAppViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    listedKeys: Set<String>,
    onPick: (PendingTarget) -> Unit,
    onEnterComponent: () -> Unit
) {
    val disabled = viewModel.disabledApps
    val packs = viewModel.packCandidates
    val trimmed = query.trim()
    val shownDisabled = remember(disabled, trimmed) {
        disabled.orEmpty().filter { it.matches(trimmed) }
    }
    val shownPacks = remember(packs, trimmed) {
        packs.orEmpty().asSequence()
            .filter { it.label().contains(trimmed, ignoreCase = true) || it.packageName.contains(trimmed, ignoreCase = true) }
            .take(MAX_PACK_ROWS)
            .toList()
    }

    Column(Modifier.navigationBarsPadding()) {
        Text(
            stringResource(R.string.addAppTitle),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.searchApps),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(Modifier.fillMaxWidth().imePadding()) {
            if (shownDisabled.isNotEmpty()) {
                item(key = "disabledHeader") { SectionHeader(stringResource(R.string.addAppSectionDisabled)) }
                items(shownDisabled, key = { "disabled:${it.key}" }) { target ->
                    val listed = target.key in listedKeys
                    CandidateRow(
                        title = target.label,
                        component = target.key,
                        listed = listed,
                        icon = { size -> rememberCandidateIcon(target.key, size) { viewModel.disabledAppIcon(target, it) } },
                        trailing = { TargetBadge(stringResource(R.string.appTargetDisabled)) },
                        onClick = { onPick(PendingTarget(target, disabled = true)) }
                    )
                }
            }
            if (packs == null) {
                item(key = "packsLoading") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.addAppLoading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (shownPacks.isNotEmpty()) {
                item(key = "packsHeader") { SectionHeader(stringResource(R.string.addAppSectionPacks)) }
                items(shownPacks, key = { "pack:${it.key}" }) { candidate ->
                    CandidateRow(
                        title = candidate.label(),
                        component = candidate.key,
                        listed = candidate.key in listedKeys,
                        icon = { size ->
                            rememberCandidateIcon("${candidate.iconPack}/${candidate.drawableName}", size) {
                                viewModel.packCandidateIcon(candidate, it)
                            }
                        },
                        trailing = {
                            Text(
                                if (candidate.packCount == 1) stringResource(R.string.addAppPackCountOne)
                                else stringResource(R.string.addAppPackCount, candidate.packCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onPick(PendingTarget(AppTarget(candidate.packageName, candidate.activityName, candidate.label()), disabled = false))
                        }
                    )
                }
            }
            if (disabled != null && packs != null && shownDisabled.isEmpty() && shownPacks.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        stringResource(R.string.addAppNothingFound),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
            item(key = "manual") {
                Surface(
                    onClick = onEnterComponent,
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.addAppEnterComponent),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.addAppEnterComponentHint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private const val MAX_PACK_ROWS = 300

private fun AppTarget.matches(query: String): Boolean =
    query.isEmpty() || label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)

private fun PackAppCandidate.label(): String = drawableName.prettyDrawableName().ifEmpty { packageName }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun rememberCandidateIcon(
    key: String,
    size: Dp,
    load: suspend (Int) -> ImageBitmap?
): ImageBitmap? {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, key, px) { value = load(px) }
    return icon
}

@Composable
private fun CandidateRow(
    title: String,
    component: String,
    listed: Boolean,
    icon: @Composable (Dp) -> ImageBitmap?,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = !listed,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val bitmap = icon(40.dp)
            Box(
                Modifier.size(40.dp).clip(IconShape).background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (bitmap != null) Image(BitmapPainter(bitmap), contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (listed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (listed) stringResource(R.string.addAppAlreadyListed) else component,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (listed) null else FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (listed) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                trailing()
            }
        }
    }
}

/** The small state label shared by the sheet and the home rows. */
@Composable
internal fun TargetBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(SwatchShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun ManualComponentForm(
    initialPackage: String,
    onBack: () -> Unit,
    onContinue: (AppTarget) -> Unit
) {
    var packageName by rememberSaveable { mutableStateOf(initialPackage) }
    var activityName by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var packageError by remember { mutableStateOf(false) }
    var activityError by remember { mutableStateOf(false) }
    val expanded = AppTargetCodec.expandActivityName(packageName.trim(), activityName.trim())

    Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.addAppBack))
            }
            Text(
                stringResource(R.string.addAppEnterComponent),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        OutlinedTextField(
            value = packageName,
            onValueChange = { packageName = it; packageError = false },
            label = { Text(stringResource(R.string.addAppPackageLabel)) },
            singleLine = true,
            isError = packageError,
            supportingText = if (packageError) {
                { Text(stringResource(R.string.addAppInvalidPackage)) }
            } else null,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            value = activityName,
            onValueChange = { activityName = it; activityError = false },
            label = { Text(stringResource(R.string.addAppActivityLabel)) },
            singleLine = true,
            isError = activityError,
            supportingText = when {
                activityError -> { { Text(stringResource(R.string.addAppInvalidActivity)) } }
                activityName.isNotBlank() && expanded != activityName.trim() -> {
                    { Text(stringResource(R.string.addAppActivityExpands, expanded)) }
                }
                else -> null
            },
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(AppTargetCodec.MAX_LABEL_LENGTH) },
            label = { Text(stringResource(R.string.addAppNameLabel)) },
            singleLine = true,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Surface(
            shape = InnerShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.addAppComponentWarning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            DialogPrimaryAction(stringResource(R.string.addAppContinue), onClick = {
                packageError = !AppTargetCodec.isValidPackageName(packageName.trim())
                activityError = !packageError && !AppTargetCodec.isValidClassName(expanded)
                if (!packageError && !activityError) {
                    AppTargetCodec.validTarget(packageName, activityName, label)?.let(onContinue)
                }
            })
        }
    }
}

@Composable
private fun AddAppConfirmDialog(
    pending: PendingTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (AppTarget, openEditor: Boolean) -> Unit
) {
    var label by rememberSaveable(pending.target.key) { mutableStateOf(pending.target.label) }
    fun confirmed() = pending.target.copy(label = label.trim().ifEmpty { pending.target.packageName })
    MessageDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        badge = { if (busy) DialogProgressBadge() else DialogBadge(Icons.Filled.Apps) },
        eyebrow = stringResource(R.string.addAppTitle),
        title = label.ifBlank { pending.target.packageName },
        actions = {
            DialogPrimaryAction(stringResource(R.string.addAppAddAndEdit), { onAdd(confirmed(), true) }, enabled = !busy)
            DialogSecondaryAction(stringResource(R.string.addAppAddOnly), { onAdd(confirmed(), false) }, enabled = !busy)
            DialogSecondaryAction(stringResource(android.R.string.cancel), onDismiss, enabled = !busy)
        }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(AppTargetCodec.MAX_LABEL_LENGTH) },
            label = { Text(stringResource(R.string.addAppNameLabel)) },
            singleLine = true,
            enabled = !busy,
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        DialogCard(title = stringResource(R.string.addAppComponent)) {
            Text(
                pending.target.key,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(
                    if (pending.disabled) R.string.addAppExplainDisabled else R.string.addAppExplainNotInstalled
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
