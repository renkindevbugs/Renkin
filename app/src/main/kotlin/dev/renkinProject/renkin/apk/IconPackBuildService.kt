package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getDefaultBackgroundColor
import dev.renkinProject.renkin.data.getDefaultIconColor
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable result needed to install and persist the exact profile snapshot that was built. */
data class BuiltIconPack(
    val uri: Uri,
    val packageName: String,
    val canBeInstalled: Boolean,
    val profileId: Long,
    val packLabel: String,
    val preferences: Preferences,
    val profileApps: List<PackageInfoStruct>,
    val preservedRows: List<DbApplication>,
    val sessionToken: ProfileSessionToken? = null,
    val undoStep: IconUndoStep? = null,
    val storageRevision: Long? = null,
    val storageEpoch: Long? = null
)

internal suspend fun installOrReportConflict(
    canUpdateInPlace: Boolean,
    install: suspend () -> ApkInstallOutcome
): ApkInstallOutcome =
    if (canUpdateInPlace) install() else ApkInstallOutcome(ApkInstallResult.CONFLICT)

internal suspend fun replaceAfterConflict(
    uninstall: suspend () -> Boolean,
    install: suspend () -> ApkInstallOutcome
): ApkInstallOutcome =
    if (uninstall()) install() else ApkInstallOutcome(ApkInstallResult.ABORTED)

internal fun excludeLockedSources(
    profileApps: List<PackageInfoStruct>,
    lockedSources: Set<String>
): List<PackageInfoStruct> = if (lockedSources.isEmpty()) {
    profileApps
} else {
    profileApps.map { app ->
        if (app.sourcePackName in lockedSources) app.changeExport(null) else app
    }
}

/**
 * Builds, signs and launches installation of icon-pack APKs. It deliberately owns no active
 * profile state; [ApplicationProvider] supplies an immutable snapshot and persists it while
 * holding the profile-operation gate.
 */
class IconPackBuildService internal constructor(
    private val context: Context,
    private val iconPackRepository: IconPackRepository,
    private val packRepository: RenkinPackRepository,
    private val lockManager: IconLockManager,
    private val profileManager: ProfileManager,
    private val iconPackCatalog: IconPackCatalog
) {
    internal data class PreparedIconPack(
        val profileId: Long,
        val preferences: Preferences,
        val profileApps: List<PackageInfoStruct>,
        val buildApps: List<PackageInfoStruct>,
        val preservedRows: List<DbApplication>,
        val calendarSelections: List<CalendarSelection>,
        val packageName: String,
        val label: String
    )

    private val buildOperations = Mutex()
    private val artifacts = ApkBuildArtifacts(context.cacheDir)

    internal suspend fun prepareBuild(
        profileId: Long,
        preferences: Preferences,
        profileApps: List<PackageInfoStruct>,
        preservedRows: List<DbApplication>
    ): PreparedIconPack = withContext(Dispatchers.Default) {
        val primaryPackName = preferences.getStringValue(PrimaryIconPackKey)
        val globalSelections = if (
            preferences.getBooleanValue(CalendarIconsKey) && primaryPackName.isNotEmpty()
        ) iconPackRepository.declaredCalendarSelections(primaryPackName) else emptyList()
        val perAppSelections = profileApps.filter { it.hasCalendarIcon }.mapNotNull { app ->
            app.calendarSourcePack(primaryPackName).takeIf { it.isNotEmpty() }?.let {
                CalendarSelection(app.toInstalledApplication(), it, app.calendarPrefix!!)
            }
        }
        val lockedSources = lockManager.lockedPacksAmong(
            profileApps.mapNotNull { it.sourcePackName?.takeIf(String::isNotEmpty) }.toSet()
        )
        val profile = packRepository.profile(profileId)
        PreparedIconPack(
            profileId, preferences, profileApps, excludeLockedSources(profileApps, lockedSources),
            preservedRows, globalSelections + perAppSelections,
            profileManager.packPackageNameFor(profileId),
            profile?.packLabel?.ifEmpty { profile.name } ?: "Renkin Pack"
        )
    }

    internal suspend fun build(
        prepared: PreparedIconPack,
        textMethod: (text: String) -> Unit,
        progressMethod: (done: Int, total: Int) -> Unit
    ): BuiltIconPack {
        var builtDirectory: File? = null
        try {
            return withContext(Dispatchers.Default) {
                buildOperations.withLock {
                    val preferences = prepared.preferences
                    val calendarData = iconPackRepository.calendarBuildData(prepared.calendarSelections)
                    val directory = artifacts.create()
                    builtDirectory = directory
                    val builder = IconPackBuilder(
                        context, prepared.buildApps, calendarData.mappings, calendarData.drawables,
                        packPackageName = prepared.packageName, packLabel = prepared.label,
                        iconPackCatalog = iconPackCatalog, apkDir = directory
                    )
                    val buildContext = currentCoroutineContext()
                    val canBeInstalled = StorageOperationGate.run { builder.canBeInstalled() }
                    val apk = builder.buildAndSign(
                        preferences.getBooleanValue(ExportThemedKey),
                        preferences.getDefaultIconColor(context).toHexString(),
                        preferences.getDefaultBackgroundColor(context).toHexString(),
                        textMethod, progressMethod, checkActive = { buildContext.ensureActive() }
                    )
                    directory.resolve("app-release-unsigned.apk").delete()
                    BuiltIconPack(
                        uri = apk, packageName = prepared.packageName, canBeInstalled = canBeInstalled,
                        profileId = prepared.profileId, packLabel = prepared.label, preferences = preferences,
                        profileApps = prepared.profileApps, preservedRows = prepared.preservedRows
                    )
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { builtDirectory?.let(artifacts::abandon) }
            throw error
        }
    }

    /** Copies a built APK to a user-chosen document; false when it is gone or the copy failed. */
    suspend fun exportApk(iconPack: BuiltIconPack, target: Uri): Boolean {
        var copied = false
        try {
            copied = artifacts.export(iconPack.uri) { context.contentResolver.openOutputStream(target, "wt") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("IconPackBuildService", "Could not export the built APK", error)
        } finally {
            // A partially written document would look like an installable APK.
            if (!copied) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            }
        }
        return copied
    }

    internal suspend fun releaseArtifacts(iconPack: BuiltIconPack, completed: Boolean = true): Unit =
        withContext(Dispatchers.IO) {
            runCatching { artifacts.release(iconPack.uri, completed) }
                .onFailure { Log.error("IconPackBuildService", "Could not release APK artifacts", it) }
            Unit
        }

    suspend fun install(
        iconPack: BuiltIconPack,
        selectionOverride: InstallerSelection? = null,
        onRoute: suspend (ApkInstallRoute) -> Unit = {}
    ): ApkInstallOutcome =
        withContext(Dispatchers.Default) {
            try {
                installOrReportConflict(iconPack.canBeInstalled) {
                    runInstall(iconPack, resolveInstaller(iconPack, selectionOverride), onRoute)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) { releaseArtifacts(iconPack, completed = false) }
                throw error
            }
        }

    suspend fun replace(
        iconPack: BuiltIconPack,
        selectionOverride: InstallerSelection? = null,
        onRoute: suspend (ApkInstallRoute) -> Unit = {}
    ): ApkInstallOutcome =
        withContext(Dispatchers.Default) {
            try {
                replaceAfterConflict(
                    uninstall = { ApkUninstaller(context).uninstall(iconPack.packageName) },
                    install = { runInstall(iconPack, resolveInstaller(iconPack, selectionOverride), onRoute) }
                )
            } catch (error: Throwable) {
                withContext(NonCancellable) { releaseArtifacts(iconPack, completed = false) }
                throw error
            }
        }

    private fun resolveInstaller(
        iconPack: BuiltIconPack,
        override: InstallerSelection?
    ): InstallerSelection = override ?: iconPack.preferences.installerSelection()

    private suspend fun runInstall(
        iconPack: BuiltIconPack,
        selection: InstallerSelection,
        onRoute: suspend (ApkInstallRoute) -> Unit
    ): ApkInstallOutcome = ApkInstaller(context, onRoute).install(
        iconPack.uri,
        iconPack.packageName,
        selection.method,
        selection.externalComponent
    )
}
