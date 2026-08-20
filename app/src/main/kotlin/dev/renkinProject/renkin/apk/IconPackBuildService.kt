package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getDefaultBackgroundColor
import dev.renkinProject.renkin.data.getDefaultIconColor
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.IconPackCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable result needed to install and persist the exact profile snapshot that was built. */
data class BuiltIconPack(
    val uri: Uri,
    val packageName: String,
    val canBeInstalled: Boolean,
    val profileId: Long,
    val packLabel: String,
    val preferences: Preferences,
    val profileApps: List<PackageInfoStruct>,
    val preservedRows: List<DbApplication>
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
    suspend fun build(
        profileId: Long,
        preferences: Preferences,
        profileApps: List<PackageInfoStruct>,
        preservedRows: List<DbApplication>,
        textMethod: (text: String) -> Unit,
        progressMethod: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BuiltIconPack = withContext(Dispatchers.Default) {
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val iconColor = preferences.getDefaultIconColor(context)
        val backgroundColor = preferences.getDefaultBackgroundColor(context)
        val primaryPackName = preferences.getStringValue(PrimaryIconPackKey)

        // Resolve calendar data at build time from the current preferences and installed packs.
        val globalSelections = if (
            preferences.getBooleanValue(CalendarIconsKey) && primaryPackName.isNotEmpty()
        ) iconPackRepository.declaredCalendarSelections(primaryPackName) else emptyList()
        val perAppSelections = profileApps
            .filter { it.hasCalendarIcon }
            .mapNotNull { app ->
                val packName = app.calendarSourcePack(primaryPackName)
                packName.takeIf { it.isNotEmpty() }?.let {
                    CalendarSelection(app.toInstalledApplication(), it, app.calendarPrefix!!)
                }
            }
        // Later selections replace only the same launcher component's global declaration.
        val calendarData = iconPackRepository.calendarBuildData(globalSelections + perAppSelections)

        // A final build-time gate prevents locked sources from shipping in the generated APK.
        val lockedSources = lockManager.lockedPacksAmong(
            profileApps.mapNotNull {
                it.sourcePackName?.takeIf { source -> source.isNotEmpty() }
            }.toSet()
        )
        val buildApps = excludeLockedSources(profileApps, lockedSources)

        val profile = packRepository.profile(profileId)
        val packLabel = profile?.packLabel?.ifEmpty { profile.name } ?: "Renkin Pack"
        val builder = IconPackBuilder(
            context,
            buildApps,
            calendarData.mappings,
            calendarData.drawables,
            packPackageName = profileManager.packPackageNameFor(profileId),
            packLabel = packLabel,
            iconPackCatalog = iconPackCatalog
        )
        val canBeInstalled = builder.canBeInstalled() // Must run before buildAndSign.
        val apk = builder.buildAndSign(
            themed,
            iconColor.toHexString(),
            backgroundColor.toHexString(),
            textMethod,
            progressMethod
        )

        BuiltIconPack(
            uri = apk,
            packageName = builder.getIconPackName(),
            canBeInstalled = canBeInstalled,
            profileId = profileId,
            packLabel = packLabel,
            preferences = preferences,
            profileApps = profileApps,
            preservedRows = preservedRows
        )
    }

    suspend fun install(iconPack: BuiltIconPack): ApkInstallOutcome =
        withContext(Dispatchers.Default) {
            installOrReportConflict(iconPack.canBeInstalled) {
                ApkInstaller(context).install(iconPack.uri, iconPack.packageName)
            }
        }

    suspend fun replace(iconPack: BuiltIconPack): ApkInstallOutcome =
        withContext(Dispatchers.Default) {
            replaceAfterConflict(
                uninstall = { ApkUninstaller(context).uninstall(iconPack.packageName) },
                install = { ApkInstaller(context).install(iconPack.uri, iconPack.packageName) }
            )
        }
}
