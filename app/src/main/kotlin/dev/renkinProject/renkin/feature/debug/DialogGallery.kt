package dev.renkinProject.renkin.feature.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.EditorDraftRecoveryState
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.ProfileActionProgress
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.IconLockManager.MissingPack
import dev.renkinProject.renkin.apk.InstallerSelection
import dev.renkinProject.renkin.apk.LostUnsavedWork
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.data.UnsavedIconJournal
import dev.renkinProject.renkin.data.UnsavedIconJournalEntry
import dev.renkinProject.renkin.data.UnsavedSessionMarker
import dev.renkinProject.renkin.data.VERDICT_PAID
import dev.renkinProject.renkin.data.VERDICT_UNKNOWN
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.transfer.BackupSnapshotStage
import dev.renkinProject.renkin.feature.backup.BackupRestorePreviewDialog
import dev.renkinProject.renkin.feature.backup.BackupRestoreProgress
import dev.renkinProject.renkin.feature.buildinstall.BuildProgressDialog
import dev.renkinProject.renkin.feature.buildinstall.InstallerPickerScreen
import dev.renkinProject.renkin.feature.buildinstall.InstallerPromptDialog
import dev.renkinProject.renkin.feature.help.InfoDialog
import dev.renkinProject.renkin.feature.help.WhatsNewDialog
import dev.renkinProject.renkin.feature.profiles.ProfileDetailsDialog
import dev.renkinProject.renkin.feature.profiles.SaveBeforeSwitchDialog
import dev.renkinProject.renkin.feature.watch.NotificationsDisabledDialog
import dev.renkinProject.renkin.ui.FullscreenDialogScaffold
import dev.renkinProject.renkin.ui.BuildOutcomeDialog
import dev.renkinProject.renkin.ui.CenteredFullscreenContent
import dev.renkinProject.renkin.ui.ColorDialog
import dev.renkinProject.renkin.ui.ConfirmClearDialog
import dev.renkinProject.renkin.ui.ConfirmDialog
import dev.renkinProject.renkin.ui.CrashLogDetailDialog
import dev.renkinProject.renkin.ui.CrashReportDialog
import dev.renkinProject.renkin.ui.EditorDraftRecoveryDialog
import dev.renkinProject.renkin.ui.EditorDraftWorkingDialog
import dev.renkinProject.renkin.ui.EditorRecoveryPreview
import dev.renkinProject.renkin.ui.GlobalBetaDialog
import dev.renkinProject.renkin.ui.IconPackStudioExportWarningDialog
import dev.renkinProject.renkin.ui.IconPackStudioWarningTarget
import dev.renkinProject.renkin.ui.InstallFailureDialog
import dev.renkinProject.renkin.ui.InstallFallbackDialog
import dev.renkinProject.renkin.ui.LostUnsavedSessionDialog
import dev.renkinProject.renkin.ui.MissingPacksDialog
import dev.renkinProject.renkin.ui.NewIconPackDialog
import dev.renkinProject.renkin.ui.OptionInfoDialog
import dev.renkinProject.renkin.ui.PackUsageDialog
import dev.renkinProject.renkin.ui.ProfileActionProgressDialog
import dev.renkinProject.renkin.ui.ProfileShareWarningDialog
import dev.renkinProject.renkin.ui.RestoreLostIconsDialog
import dev.renkinProject.renkin.ui.RestoringModifiersDialog
import dev.renkinProject.renkin.ui.RulerPickerDialog
import dev.renkinProject.renkin.ui.SettingsSectionHeader
import dev.renkinProject.renkin.ui.WatchProfileGoneDialog
import dev.renkinProject.renkin.ui.percentRuler
import dev.renkinProject.renkin.util.CrashReporter

/**
 * Debug-only catalogue of the app's modals, each rendered by its production composable with mock
 * data, so their look can be reviewed without reproducing the situation that normally opens them.
 * Actions only close the preview; nothing is written.
 */
private class GalleryEntry(val id: String, val label: String, val content: @Composable (close: () -> Unit) -> Unit)

private class GallerySection(val title: String, val entries: List<GalleryEntry>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialogGalleryScreen(onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    // A real profile reads more naturally than a placeholder; the fallback covers a single-profile install.
    val otherProfileName = remember(profiles) {
        profiles.filter { it.id != viewModel.sessionToken.profileId }.randomOrNull()?.name ?: "Work setup"
    }
    val sections = remember(otherProfileName) { gallerySections(otherProfileName) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val redesigns = remember { dialogRedesigns() }
    var showRedesign by rememberSaveable { mutableStateOf(false) }

    FullscreenDialogScaffold(title = stringResource(R.string.dialogGallery), onDismiss = onDismiss) { innerPadding ->
        CenteredFullscreenContent(Modifier.padding(innerPadding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                sections.forEach { section ->
                    SettingsSectionHeader(section.title)
                    section.entries.forEach { entry ->
                        GalleryRow(
                            entry,
                            hasRedesign = entry.id in redesigns,
                            onCurrent = { openId = entry.id; showRedesign = false },
                            onRedesign = { openId = entry.id; showRedesign = true }
                        )
                    }
                }
            }
        }
        sections.flatMap { it.entries }.firstOrNull { it.id == openId }?.let { entry ->
            val content = redesigns[entry.id]?.takeIf { showRedesign } ?: entry.content
            content { openId = null }
        }
    }
}

/** Current and redesign sit side by side so one entry compares in two taps without scrolling. */
@Composable
private fun GalleryRow(entry: GalleryEntry, hasRedesign: Boolean, onCurrent: () -> Unit, onRedesign: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyLarge)
            Text(entry.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onCurrent, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Current") }
        if (hasRedesign) {
            FilledTonalButton(onClick = onRedesign, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("New") }
        }
    }
}

private fun gallerySections(otherProfileName: String): List<GallerySection> = listOf(
    GallerySection("Build & install", buildInstallEntries()),
    GallerySection("Profiles", profileEntries(otherProfileName)),
    GallerySection("Unsaved work & recovery", recoveryEntries(otherProfileName)),
    GallerySection("Backup & sharing", backupEntries()),
    GallerySection("Icon packs & watch", packEntries()),
    GallerySection("Editor", editorEntries()),
    GallerySection("Settings & help", settingsEntries())
)

internal const val MOCK_PACK_LABEL = "Renkin Pastel"

private fun buildInstallEntries(): List<GalleryEntry> {
    val outcome = MainViewModel.BuildOutcomeInfo(
        MainViewModel.BuildOutcome.FIRST_INSTALL, MOCK_PACK_LABEL, "System installer",
        savingProfile = false, profileSaveFailed = false
    )
    val failure = MainViewModel.InstallFailureInfo(
        summary = "INSTALL_FAILED_ABORTED: User rejected permissions",
        report = "Mock install failure report",
        missingSystemInstaller = false,
        canSaveApk = true
    )
    return listOf(
        GalleryEntry("build.progress", "Building pack — per-app step") { close ->
            BuildProgressDialog("Packing icons…", 128 to 312, onPreviewDismiss = close)
        },
        GalleryEntry("build.progressInstall", "Building pack — installing") { close ->
            BuildProgressDialog("Installing via System installer…", null, onPreviewDismiss = close)
        },
        GalleryEntry("install.first", "Installed — first install") { close ->
            BuildOutcomeDialog(outcome, close)
        },
        GalleryEntry("install.update", "Updated — refresh cached icons") { close ->
            BuildOutcomeDialog(outcome.copy(outcome = MainViewModel.BuildOutcome.UPDATE), close)
        },
        GalleryEntry("install.saving", "Installed — still saving profile") { close ->
            BuildOutcomeDialog(outcome.copy(savingProfile = true), close)
        },
        GalleryEntry("install.saveFailed", "Installed — profile save failed") { close ->
            BuildOutcomeDialog(outcome.copy(profileSaveFailed = true), close)
        },
        GalleryEntry("install.prompt", "Choose installer — first choice") { close ->
            InstallerPromptDialog(InstallerSelection(InstallMethod.SYSTEM), explainSavedChoice = true,
                onSelect = { close() }, onDismiss = close)
        },
        GalleryEntry("install.picker", "Installer picker screen") { close ->
            InstallerPickerScreen(InstallerSelection(InstallMethod.SYSTEM), askEveryTime = false,
                onSelect = {}, onAskEveryTimeChange = {}, onDismiss = close)
        },
        GalleryEntry("install.fallback", "Replace installed pack") { close ->
            InstallFallbackDialog(signatureMismatch = false, onConfirm = close, onDismiss = close)
        },
        GalleryEntry("install.fallbackSignature", "Replace installed pack — signature mismatch") { close ->
            InstallFallbackDialog(signatureMismatch = true, onConfirm = close, onDismiss = close)
        },
        GalleryEntry("install.failure", "Install failed — generic") { close ->
            InstallFailureDialog(failure, onChooseInstaller = close, onSaveApk = close, onDismiss = close)
        },
        GalleryEntry("install.noSystemInstaller", "Install failed — no system installer") { close ->
            InstallFailureDialog(failure.copy(missingSystemInstaller = true, canSaveApk = false),
                onChooseInstaller = close, onSaveApk = close, onDismiss = close)
        }
    )
}

private fun profileEntries(otherProfileName: String) = listOf(
    GalleryEntry("profile.progress", "Switching profile — progress") { close ->
        ProfileActionProgressDialog(ProfileActionProgress(R.string.profileSaveAndSwitchInProgress), onPreviewDismiss = close)
    },
    GalleryEntry("profile.progressApp", "Generating icon — progress with app") { close ->
        ProfileActionProgressDialog(ProfileActionProgress(R.string.iconGenerationInProgress, "Spotify"),
            onPreviewDismiss = close)
    },
    GalleryEntry("profile.saveBeforeSwitch", "Save before switching") { close ->
        SaveBeforeSwitchDialog(onSave = close, onDiscard = close, onDismiss = close)
    },
    GalleryEntry("profile.create", "Create profile") { close ->
        ProfileDetailsDialog(
            title = stringResource(R.string.createProfileTitle),
            confirmLabel = stringResource(R.string.createProfileAction),
            onConfirm = { _, _, _, _, _ -> close() },
            onDismiss = close
        )
    },
    GalleryEntry("profile.edit", "Edit profile") { close ->
        ProfileDetailsDialog(
            title = stringResource(R.string.editProfile),
            confirmLabel = stringResource(R.string.saveAction),
            initialName = otherProfileName,
            initialDescription = "Muted colours for the work phone",
            initialPackLabel = MOCK_PACK_LABEL,
            onConfirm = { _, _, _, _, _ -> close() },
            onDismiss = close
        )
    },
    GalleryEntry("profile.delete", "Delete profile") { close ->
        ConfirmDialog(
            title = stringResource(R.string.deleteProfile),
            text = stringResource(R.string.deleteProfileText, otherProfileName),
            icon = Icons.Filled.Delete,
            onConfirm = close,
            onDismiss = close
        )
    }
)

private fun recoveryEntries(otherProfileName: String): List<GalleryEntry> {
    val checkpoint = EditorDraftCheckpoint(storageGeneration = "gallery", draftId = "gallery")
    val offer = EditorDraftRecoveryState.Offer(checkpoint, "Spotify", attempt = 1, profileName = otherProfileName,
        preview = EditorRecoveryPreview(mockIcon(0xFF1DB954.toInt()), mockIcon(0xFF7C5CFF.toInt())))
    val marker = UnsavedSessionMarker("gallery", profileId = 2, editedCount = 3, refreshedCount = 41)
    val journal = UnsavedIconJournal("gallery", profileId = 2,
        entries = List(4) { UnsavedIconJournalEntry("com.example.app$it/.Main", null) }, droppedCount = 1)
    val recovery: @Composable (EditorDraftRecoveryState, () -> Unit) -> Unit = { state, close ->
        EditorDraftRecoveryDialog(state, { close() }, { close() }, { close() }, { close() }) { close() }
    }
    return listOf(
        GalleryEntry("draft.offer", "Editor draft — restore offer") { close -> recovery(offer, close) },
        GalleryEntry("draft.offerNoPreview", "Editor draft — offer, preview missing") { close ->
            recovery(offer.copy(preview = EditorRecoveryPreview(null, null), profileName = null), close)
        },
        GalleryEntry("draft.otherProfile", "Editor draft — in another profile") { close ->
            recovery(EditorDraftRecoveryState.NeedsProfile(checkpoint, 2, otherProfileName), close)
        },
        GalleryEntry("draft.working", "Editor draft — working") { close -> EditorDraftWorkingDialog(onPreviewDismiss = close) },
        GalleryEntry("draft.changed", "Editor draft — app changed") { close ->
            recovery(EditorDraftRecoveryState.Changed(checkpoint), close)
        },
        GalleryEntry("draft.failed", "Editor draft — restore failed") { close ->
            recovery(EditorDraftRecoveryState.Failed(checkpoint, null), close)
        },
        GalleryEntry("lost.session", "Lost unsaved session — edited + refreshed") { close ->
            LostUnsavedSessionDialog(marker, otherProfileName, canRefresh = true, onRefresh = close, onDismiss = close)
        },
        GalleryEntry("lost.sessionNoProfile", "Lost unsaved session — profile deleted") { close ->
            LostUnsavedSessionDialog(marker.copy(refreshedCount = 0), null, canRefresh = false,
                onRefresh = close, onDismiss = close)
        },
        GalleryEntry("lost.restore", "Restore lost icons") { close ->
            RestoreLostIconsDialog(LostUnsavedWork(marker.copy(providerInstanceId = "gallery"), journal),
                otherProfileName, onRestore = close, onRefresh = close, onLater = close, onDiscard = close)
        },
        GalleryEntry("settings.restorePrevious", "Restore previous state") { close ->
            ConfirmDialog(stringResource(R.string.restorePreviousState),
                stringResource(R.string.restorePreviousStateConfirmation), close, close, icon = Icons.Filled.Restore)
        },
        GalleryEntry("settings.discardPrevious", "Discard previous state") { close ->
            ConfirmDialog(stringResource(R.string.discardPreviousState),
                stringResource(R.string.discardPreviousStateConfirmation), close, close, icon = Icons.Filled.Delete)
        },
        GalleryEntry("settings.discardAbandoned", "Discard abandoned recovery") { close ->
            ConfirmDialog(stringResource(R.string.discardAbandonedRecovery),
                stringResource(R.string.discardAbandonedRecoveryConfirmation), close, close, icon = Icons.Filled.Delete)
        },
        GalleryEntry("backup.keepCurrent", "Storage recovery — keep current") { close ->
            ConfirmDialog(stringResource(R.string.backupRecoveryKeepCurrent),
                stringResource(R.string.backupRecoveryKeepCurrentConfirmation), close, close)
        }
    )
}

private fun backupEntries(): List<GalleryEntry> {
    val preview = BackupManager.BackupPreview(
        appVersion = "2026.10.03",
        exportedAt = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000,
        profiles = listOf(
            BackupManager.ProfilePreview("Default", 214),
            BackupManager.ProfilePreview("Work setup", 87),
            BackupManager.ProfilePreview("Monochrome", 156)
        ),
        activeWatchRules = 3,
        completedWatchRules = 5,
        savedStyles = 7,
        uploadedImages = 12
    )
    return listOf(
        GalleryEntry("backup.preview", "Restore backup — preview") { close ->
            BackupRestorePreviewDialog(preview, onConfirm = close, onDismiss = close)
        },
        GalleryEntry("backup.progressSaving", "Restore — saving current state") { close ->
            BackupRestoreProgress(BackupManager.RestoreStage.SAVING_CURRENT_STATE, BackupSnapshotStage.ICONS,
                cancellable = true, cancelling = false, onCancel = close, onPreviewDismiss = close)
        },
        GalleryEntry("backup.progressRestoring", "Restore — restoring data (not cancellable)") { close ->
            BackupRestoreProgress(BackupManager.RestoreStage.RESTORING_DATA, null,
                cancellable = false, cancelling = false, onCancel = close, onPreviewDismiss = close)
        },
        GalleryEntry("backup.progressCancelling", "Restore — cancelling") { close ->
            BackupRestoreProgress(BackupManager.RestoreStage.VALIDATING_SAVED_STATE, null,
                cancellable = false, cancelling = true, onCancel = close, onPreviewDismiss = close)
        },
        GalleryEntry("share.warning", "Share profile warning") { close ->
            ProfileShareWarningDialog(onShare = { close() }, onDismiss = close)
        },
        GalleryEntry("share.ipsProfile", "Icon Pack Studio warning — profile") { close ->
            IconPackStudioExportWarningDialog(IconPackStudioWarningTarget.PROFILE, onContinue = close, onDismiss = close)
        },
        GalleryEntry("share.ipsBackup", "Icon Pack Studio warning — backup") { close ->
            IconPackStudioExportWarningDialog(IconPackStudioWarningTarget.BACKUP, onContinue = close, onDismiss = close)
        }
    )
}

private fun packEntries() = listOf(
    GalleryEntry("packs.new", "New icon pack installed") { close ->
        NewIconPackDialog("Arcticons", onReload = close, onDismiss = close)
    },
    GalleryEntry("packs.missing", "Missing icon packs") { close ->
        MissingPacksDialog(
            listOf(
                MissingPack("com.donnnno.arcticons", "Arcticons", VERDICT_UNKNOWN, 42),
                MissingPack("com.example.paidpack", "Delta Pro", VERDICT_PAID, 17),
                MissingPack("com.example.lines", "Lines Dark", VERDICT_UNKNOWN, 3)
            )
        ) { close() }
    },
    GalleryEntry("packs.usage", "Pack usage stats") { close ->
        PackUsageDialog(
            listOf(
                ApplicationProvider.PackUsage("com.donnnno.arcticons", "Arcticons", 128, installed = true),
                ApplicationProvider.PackUsage("com.example.lines", "Lines Dark", 54, installed = true),
                ApplicationProvider.PackUsage("com.example.paidpack", "Delta Pro", 17, installed = false),
                ApplicationProvider.PackUsage("com.example.unused", "Unused Pack", 0, installed = true)
            ),
            close
        )
    },
    GalleryEntry("packs.usageLoading", "Pack usage stats — loading") { close -> PackUsageDialog(null, close) },
    GalleryEntry("packs.usageEmpty", "Pack usage stats — empty") { close -> PackUsageDialog(emptyList(), close) },
    GalleryEntry("watch.profileGone", "Watch — profile deleted") { close -> WatchProfileGoneDialog(close) },
    GalleryEntry("watch.notificationsOff", "Watch — notifications disabled") { close ->
        NotificationsDisabledDialog(onOpenSettings = close, onDismiss = close)
    },
    GalleryEntry("watch.deleteRule", "Watch — delete rule") { close ->
        ConfirmDialog(stringResource(R.string.deleteRuleTitle), stringResource(R.string.deleteRuleText), close, close)
    },
    GalleryEntry("watch.deleteCompleted", "Watch — delete all completed") { close ->
        ConfirmDialog(stringResource(R.string.deleteAllCompletedTitle),
            stringResource(R.string.deleteAllCompletedText), close, close)
    }
)

private fun editorEntries() = listOf(
    GalleryEntry("editor.restoringModifiers", "Restoring saved modifiers") { close -> RestoringModifiersDialog(close) },
    GalleryEntry("editor.confirmClear", "Clear icon") { close -> ConfirmClearDialog(onDismiss = close, onIconClear = close) },
    GalleryEntry("editor.optionInfo", "Option info") { close ->
        OptionInfoDialog(stringResource(R.string.globalBetaText), close)
    },
    GalleryEntry("editor.color", "Colour picker") { close ->
        ColorDialog(onDismiss = close, currentlySelected = Color(0xFF7C5CFF), onColorSelected = { close() })
    },
    GalleryEntry("editor.ruler", "Ruler value picker") { close ->
        var value by remember { mutableStateOf(0.42f) }
        RulerPickerDialog("Scale", value, 0f..1f, percentRuler(), onValueChange = { value = it }, onDismiss = close)
    },
    GalleryEntry("global.beta", "Global options — beta notice") { close ->
        GlobalBetaDialog(onOpenIssues = close, onDismiss = close)
    },
    GalleryEntry("global.discard", "Global options — discard changes") { close ->
        ConfirmDialog(stringResource(R.string.globalDiscardTitle), stringResource(R.string.globalDiscardText), close, close)
    }
)

private fun settingsEntries() = listOf(
    GalleryEntry("settings.clearIcons", "Clear all icons") { close ->
        ConfirmDialog(stringResource(R.string.clearIconsTitle), stringResource(R.string.clearIconsText),
            close, close, icon = Icons.Filled.DeleteSweep)
    },
    GalleryEntry("help.whatsNew", "What's new (startup)") { close -> WhatsNewDialog(close) },
    GalleryEntry("help.about", "About") { close -> InfoDialog(close) },
    GalleryEntry("crash.report", "Crash report after restart") { close -> CrashReportDialog(close) },
    GalleryEntry("crash.detail", "Crash log detail") { close ->
        CrashLogDetailDialog(
            CrashReporter.CrashEntry("gallery", System.currentTimeMillis(),
                "java.lang.IllegalStateException: Mock crash for the dialog gallery\n" +
                    "\tat dev.renkinProject.renkin.MainViewModel.build(MainViewModel.kt:812)\n" +
                    "\tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)"),
            onDismiss = close,
            onDelete = close
        )
    },
    GalleryEntry("crash.clearAll", "Clear all crash logs") { close ->
        ConfirmDialog(stringResource(R.string.crashLogsClearAllTitle), stringResource(R.string.crashLogsClearAllText),
            close, close)
    }
)

internal fun mockIcon(color: Int): Bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
    Canvas(bitmap).drawCircle(96f, 96f, 88f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
}
