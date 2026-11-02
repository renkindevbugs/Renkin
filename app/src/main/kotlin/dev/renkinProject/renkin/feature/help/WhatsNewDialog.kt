@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.renkinProject.renkin.feature.help

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.LATEST_WHATS_NEW_VERSION_NAME
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.FullscreenDialogScaffold
import dev.renkinProject.renkin.ui.CenteredFullscreenContent
import dev.renkinProject.renkin.ui.RenkinAlertDialog
import dev.renkinProject.renkin.ui.boldStringResource
import dev.renkinProject.renkin.ui.drawVerticalScrollbar

private data class WhatsNewRelease(
    val version: String,
    val sections: List<WhatsNewSection>
)

private data class WhatsNewSection(
    @get:StringRes val title: Int,
    val changes: List<Int>
)

private val previousNew = listOf(
    R.string.whatsNewShadow,
    R.string.whatsNewAutomaticBackups,
    R.string.whatsNewInstallerChoice,
    R.string.whatsNewIntro
)
private val previousChanged = listOf(
    R.string.whatsNewColorize
)
private val previousFixed = listOf(R.string.whatsNewWatch)

private val releaseHistory = listOf(
    WhatsNewRelease(
        LATEST_WHATS_NEW_VERSION_NAME,
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionNew,
                listOf(
                    R.string.whatsNewGlobalStyleTools,
                    R.string.whatsNewEditRecovery,
                    R.string.whatsNewPackIdentity,
                    R.string.whatsNewAddedApps,
                    R.string.whatsNewMatchAppColor,
                    R.string.whatsNewAppIconVariants,
                    R.string.whatsNewRenkinSources,
                    R.string.whatsNewShevery
                )
            ),
            WhatsNewSection(
                R.string.whatsNewSectionChanged,
                listOf(
                    R.string.whatsNewSourceSelection,
                    R.string.whatsNewPreviewAndPresets,
                    R.string.whatsNewNavigationAndAccessibility,
                    R.string.whatsNewLanguageSetting
                )
            ),
            WhatsNewSection(
                R.string.whatsNewSectionFixed,
                listOf(
                    R.string.whatsNewPackUpdates,
                    R.string.whatsNewAdaptiveRendering,
                    R.string.whatsNewProfileAndBackupReliability,
                    R.string.whatsNewLargeCollections
                )
            )
        )
    ),
    WhatsNewRelease(
        "2026.09.02",
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionFixed,
                listOf(
                    R.string.whatsNewThemedIconState,
                    R.string.whatsNewSegmentPreview,
                    R.string.whatsNewThemedPackPreview
                )
            )
        )
    ),
    WhatsNewRelease(
        "2026.09.01",
        listOf(
            WhatsNewSection(R.string.whatsNewSectionNew, previousNew),
            WhatsNewSection(R.string.whatsNewSectionChanged, previousChanged),
            WhatsNewSection(R.string.whatsNewSectionFixed, previousFixed)
        )
    ),
    WhatsNewRelease(
        "2026.08.03",
        listOf(
            WhatsNewSection(R.string.whatsNewSectionFixed, listOf(R.string.whatsNewInstallFixes))
        )
    ),
    WhatsNewRelease(
        "2026.08.02",
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionNew,
                listOf(
                    R.string.whatsNewGlobalStyle,
                    R.string.whatsNewModifierPresets,
                    R.string.whatsNewBackgroundBrushes
                )
            ),
            WhatsNewSection(R.string.whatsNewSectionFixed, listOf(R.string.whatsNewDuplicatePicker))
        )
    ),
    WhatsNewRelease(
        "2026.08.01",
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionNew,
                listOf(
                    R.string.whatsNewGradients,
                    R.string.whatsNewSegments,
                    R.string.whatsNewBuildChanges,
                    R.string.whatsNewQuickActions
                )
            ),
            WhatsNewSection(R.string.whatsNewSectionChanged, listOf(R.string.whatsNewWideLayouts))
        )
    ),
    WhatsNewRelease(
        "2026.07.05",
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionNew,
                listOf(
                    R.string.whatsNewGlobalModifiers,
                    R.string.whatsNewOnlineIcons,
                    R.string.whatsNewMaterialOptions
                )
            ),
            WhatsNewSection(R.string.whatsNewSectionChanged, listOf(R.string.whatsNewResponsiveLayouts))
        )
    ),
    WhatsNewRelease(
        "2026.07.04",
        listOf(
            WhatsNewSection(R.string.whatsNewSectionChanged, listOf(R.string.whatsNewSharingLocks))
        )
    ),
    WhatsNewRelease(
        "2026.07.03",
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionNew,
                listOf(
                    R.string.whatsNewOutline,
                    R.string.whatsNewFirstIntro,
                    R.string.whatsNewLauncherSupport
                )
            ),
            WhatsNewSection(R.string.whatsNewSectionFixed, listOf(R.string.whatsNewLawniconsFix))
        )
    ),
    WhatsNewRelease(
        "2026.07.02",
        listOf(
            WhatsNewSection(R.string.whatsNewSectionChanged, listOf(R.string.whatsNewPaidPackSharing))
        )
    ),
    WhatsNewRelease(
        "2026.07.01",
        listOf(
            WhatsNewSection(
                R.string.whatsNewSectionNew,
                listOf(
                    R.string.whatsNewProfiles,
                    R.string.whatsNewBackup,
                    R.string.whatsNewTextSvg,
                    R.string.whatsNewMaterialShapes
                )
            )
        )
    )
)

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.NewReleases,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.whatsNewTitle))
                Text(
                    text = stringResource(R.string.whatsNewVersion, LATEST_WHATS_NEW_VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            ReleaseSections(
                release = releaseHistory.first(),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        }
    )
}

@Composable
fun WhatsNewScreen(onDismiss: () -> Unit) {
    FullscreenDialogScaffold(
        title = stringResource(R.string.whatsNewTitle),
        onDismiss = onDismiss,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    ) { innerPadding ->
        CenteredFullscreenContent(Modifier.padding(innerPadding)) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawVerticalScrollbar(listState),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    Icon(
                        imageVector = Icons.Outlined.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.whatsNewTitle),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = stringResource(
                            R.string.whatsNewLatestCurrent,
                            LATEST_WHATS_NEW_VERSION_NAME,
                            BuildConfig.VERSION_NAME
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                releaseHistory.forEach { release ->
                    item(key = release.version) {
                        ReleaseNotes(release)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(release: WhatsNewRelease) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.whatsNewReleaseVersion, release.version),
            style = MaterialTheme.typography.headlineSmall
        )
        ReleaseSections(release)
    }
}

@Composable
private fun ReleaseSections(release: WhatsNewRelease, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        release.sections.forEach { section ->
            Text(
                text = stringResource(section.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
            section.changes.forEach { change -> WhatsNewItem(change) }
        }
    }
}

@Composable
private fun WhatsNewItem(@StringRes text: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = boldStringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
