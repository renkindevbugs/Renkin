package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.ProfilePackIconCodec
import dev.renkinProject.renkin.drawable.IconPackDrawable
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
    sameSigner: Boolean? = null,
    install: suspend () -> ApkInstallOutcome
): ApkInstallOutcome = when {
    sameSigner == false -> ApkInstallOutcome(ApkInstallResult.CONFLICT, SIGNATURE_MISMATCH_DETAIL)
    canUpdateInPlace -> install()
    else -> ApkInstallOutcome(ApkInstallResult.CONFLICT)
}

internal suspend fun replaceAfterConflict(
    uninstall: suspend () -> Boolean,
    install: suspend () -> ApkInstallOutcome,
    uninstallFailed: () -> ApkInstallOutcome = { ApkInstallOutcome(ApkInstallResult.ABORTED) }
): ApkInstallOutcome =
    if (uninstall()) install() else uninstallFailed()

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
        val label: String,
        val packIcon: IconPackDrawable? = null,
        val packIconSource: String? = null
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
        validateProfilePackIdentity(context, profileId, profile?.customPackageName.orEmpty(), packRepository.profiles())
        val packIcon = ProfilePackIconCodec(context).decode(profile?.packIcon.orEmpty())
        val iconSource = packIcon?.sourcePackName?.takeIf { it.isNotEmpty() }
        check(iconSource == null || lockManager.lockedPacksAmong(setOf(iconSource)).isEmpty()) {
            context.getString(R.string.profilePackIconLocked)
        }
        PreparedIconPack(
            profileId, preferences, profileApps, excludeLockedSources(profileApps, lockedSources),
            preservedRows, globalSelections + perAppSelections,
            profileManager.packPackageNameFor(profileId),
            profile?.packLabel?.ifEmpty { profile.name } ?: "Renkin Pack",
            packIcon?.icon, iconSource
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
                        iconPackCatalog = iconPackCatalog, apkDir = directory, packIcon = prepared.packIcon,
                        packIconSource = prepared.packIconSource
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

    internal suspend fun reserveApkExport(source: Uri): ApkExportLease? {
        var reserved: ApkExportLease? = null
        try {
            return withContext(Dispatchers.IO) { artifacts.reserveExport(source).also { reserved = it } }
        } catch (error: Throwable) {
            reserved?.let { withContext(NonCancellable) { releaseApkExport(it) } }
            throw error
        }
    }

    internal suspend fun releaseApkExport(lease: ApkExportLease) = withContext(Dispatchers.IO) {
        try {
            artifacts.releaseExport(lease)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("IconPackBuildService", "Could not release APK export reservation", error)
        }
    }

    /** Copies the reserved APK; an orphaned picker result also removes its empty document. */
    internal suspend fun exportApk(lease: ApkExportLease?, target: Uri): Boolean {
        var copied = false
        try {
            copied = lease != null && artifacts.export(lease) { context.contentResolver.openOutputStream(target, "wt") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("IconPackBuildService", "Could not export the built APK", error)
        } finally {
            if (lease != null) withContext(NonCancellable) { releaseApkExport(lease) }
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
                if (hasForeignCustomTarget(iconPack)) return@withContext occupiedPackageOutcome()
                val sameSigner = iconPack.uri.path?.let { path ->
                    withContext(Dispatchers.IO) { canUpdateWithSameSigner(context.packageManager, iconPack.packageName, path) }
                }
                installOrReportConflict(iconPack.canBeInstalled, sameSigner) {
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
                if (hasForeignCustomTarget(iconPack)) return@withContext occupiedPackageOutcome()
                replaceAfterConflict(
                    uninstall = { ApkUninstaller(context).uninstall(iconPack.packageName, allUsers = true) },
                    install = { runInstall(iconPack, resolveInstaller(iconPack, selectionOverride), onRoute) },
                    // A pack this app cannot see was not cancelled by the user: Android keeps a record
                    // of it that only an all-users uninstall (Shizuku or adb) removes.
                    uninstallFailed = {
                        if (iconPackCatalog.packageInfo(iconPack.packageName) != null) ApkInstallOutcome(ApkInstallResult.ABORTED)
                        else unseenPackRecordOutcome(iconPack.packageName)
                    }
                )
            } catch (error: Throwable) {
                withContext(NonCancellable) { releaseArtifacts(iconPack, completed = false) }
                throw error
            }
        }

    private fun hasForeignCustomTarget(iconPack: BuiltIconPack): Boolean =
        iconPack.packageName != automaticPackPackageName(iconPack.profileId) &&
            GeneratedPackIdentity.hasForeignInstalledPackage(context, iconPack.packageName)

    private fun unseenPackRecordOutcome(packageName: String) = ApkInstallOutcome(
        ApkInstallResult.FAILED, context.getString(R.string.packRecordBlocksReplace, packageName)
    )

    private fun occupiedPackageOutcome() = ApkInstallOutcome(
        ApkInstallResult.BLOCKED, context.getString(R.string.profilePackageOccupied)
    )

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
