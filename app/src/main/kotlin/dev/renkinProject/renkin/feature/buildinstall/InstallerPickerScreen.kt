@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.feature.buildinstall

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.CenteredFullscreenContent
import dev.renkinProject.renkin.ui.drawVerticalScrollbar
import dev.renkinProject.renkin.apk.InstallerOption
import dev.renkinProject.renkin.apk.InstallerSelection
import dev.renkinProject.renkin.apk.ShizukuState
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.DialogShape
import dev.renkinProject.renkin.ui.theme.InnerShape

@Composable
fun InstallerPickerScreen(
    selected: InstallerSelection,
    askEveryTime: Boolean,
    onSelect: (InstallerSelection) -> Unit,
    onAskEveryTimeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    val options = installerOptions(viewModel)
    val shizukuActions = shizukuActions(viewModel)
    val listState = rememberLazyListState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.installerSelect)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.close)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            ) { innerPadding ->
                CenteredFullscreenContent(Modifier.padding(innerPadding)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawVerticalScrollbar(listState),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        installerItems(options, selected, onSelect, shizukuActions)

                        item(key = "ask_every_time") {
                            AskEveryTimeCard(
                                checked = askEveryTime,
                                onCheckedChange = onAskEveryTimeChange,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InstallerPromptDialog(
    selected: InstallerSelection,
    explainSavedChoice: Boolean,
    onSelect: (InstallerSelection) -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: MainViewModel = hiltViewModel()
    val options = installerOptions(viewModel)
    val shizukuActions = shizukuActions(viewModel)
    val listState = rememberLazyListState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
        ) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(
                    text = stringResource(R.string.installerSelect),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                if (explainSavedChoice) {
                    Text(
                        text = stringResource(R.string.installerFirstChoiceDescription),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .drawVerticalScrollbar(listState),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    installerItems(options, selected, onSelect, shizukuActions)
                }
            }
        }
    }
}

@Composable
private fun installerOptions(viewModel: MainViewModel): List<InstallerOption> {
    val discovered by produceState<List<InstallerOption>>(emptyList()) {
        value = viewModel.installerEntries()
    }
    LaunchedEffect(Unit) { viewModel.refreshShizukuState() }
    val shizukuState = viewModel.shizukuState
    val shizukuDetail = shizukuDescription(shizukuState)
    return remember(discovered, shizukuState, shizukuDetail) {
        discovered.map { option ->
            if (option.selection.method == InstallMethod.SHIZUKU) {
                option.copy(
                    description = shizukuDetail,
                    available = shizukuState != ShizukuState.UNSUPPORTED
                )
            } else {
                option
            }
        }
    }
}

private fun LazyListScope.installerItems(
    options: List<InstallerOption>,
    selected: InstallerSelection,
    onSelect: (InstallerSelection) -> Unit,
    shizukuActions: ShizukuActions
) {
    items(options, key = { it.selection.preferenceToken }) { option ->
        InstallerOptionCard(
            option = option,
            selected = option.selection == selected,
            onClick = {
                if (option.selection.method == InstallMethod.SHIZUKU) {
                    shizukuActions.onRequestPermission()
                }
                onSelect(option.selection)
            },
            onOpenShizuku = shizukuActions.onOpenManager
                ?.takeIf { option.selection.method == InstallMethod.SHIZUKU }
        )
    }
}

private val InstallerSelection.preferenceToken: String
    get() = "${method.name}:$externalComponent"

private class ShizukuActions(
    val onRequestPermission: () -> Unit,
    // Null when opening the Shizuku app would not help: ready, missing or unsupported.
    val onOpenManager: (() -> Unit)?
)

@Composable
private fun shizukuActions(viewModel: MainViewModel): ShizukuActions {
    val canFixInShizukuApp = viewModel.shizukuState in setOf(
        ShizukuState.NOT_RUNNING,
        ShizukuState.OUTDATED,
        ShizukuState.PERMISSION_DENIED
    )
    return ShizukuActions(
        onRequestPermission = viewModel::requestShizukuPermission,
        onOpenManager = if (canFixInShizukuApp) {
            { viewModel.openShizukuManager() }
        } else null
    )
}

@Composable
private fun shizukuDescription(state: ShizukuState): String = stringResource(
    when (state) {
        ShizukuState.UNSUPPORTED -> R.string.shizukuUnsupported
        ShizukuState.NOT_INSTALLED -> R.string.shizukuNotInstalled
        ShizukuState.NOT_RUNNING -> R.string.shizukuNotRunning
        ShizukuState.OUTDATED -> R.string.shizukuOutdated
        ShizukuState.PERMISSION_REQUIRED -> R.string.shizukuPermissionRequired
        ShizukuState.PERMISSION_DENIED -> R.string.shizukuPermissionDenied
        ShizukuState.READY -> R.string.shizukuReady
        ShizukuState.ERROR -> R.string.shizukuError
    }
)

@Composable
private fun InstallerOptionCard(
    option: InstallerOption,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenShizuku: (() -> Unit)? = null
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (option.available) 1f else 0.55f)
            .border(if (selected) 1.5.dp else 0.5.dp, borderColor, CardShape)
            .clickable(enabled = option.available, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                enabled = option.available,
                onClick = onClick
            )
            Spacer(Modifier.width(10.dp))
            if (option.icon != null) {
                Image(
                    bitmap = option.icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp).clip(InnerShape)
                )
            } else {
                Icon(
                    imageVector = if (option.selection.method == InstallMethod.SHIZUKU) {
                        Icons.Filled.Android
                    } else {
                        Icons.Filled.InstallMobile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onOpenShizuku != null) {
                    TextButton(
                        onClick = onOpenShizuku,
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        Text(stringResource(R.string.shizukuOpenApp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AskEveryTimeCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.installerAskEveryTime),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.installerAskEveryTimeDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
