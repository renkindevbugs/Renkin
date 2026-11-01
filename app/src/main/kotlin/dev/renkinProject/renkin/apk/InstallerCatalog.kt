package dev.renkinProject.renkin.apk

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import dev.renkinProject.renkin.R
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ExternalInstallerComponentKey
import dev.renkinProject.renkin.data.INSTALL_METHOD_DEFAULT
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.data.InstallMethodKey
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull

data class InstallerSelection(
    val method: InstallMethod,
    val externalComponent: String = ""
)

fun Preferences.installerSelection(): InstallerSelection = InstallerSelection(
    method = getEnumValue(InstallMethodKey, INSTALL_METHOD_DEFAULT),
    externalComponent = getStringValue(ExternalInstallerComponentKey)
)

data class InstallerOption(
    val selection: InstallerSelection,
    val label: String,
    val description: String,
    val icon: Bitmap?,
    val available: Boolean = true
)

/** Discovers genuine APK handlers without exposing file/package-manager work to Compose. */
class InstallerCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val dummyUri = Uri.parse("content://${appContext.packageName}.fileProvider/installer-picker.apk")

    fun entries(): List<InstallerOption> {
        val systemComponent = resolveSystemInstaller()
        val systemAvailable = hasSystemInstallActivity(packageManager, dummyUri)
        val fixed = buildList {
            add(
                InstallerOption(
                    selection = InstallerSelection(InstallMethod.SYSTEM),
                    label = appContext.getString(R.string.installerSystem),
                    description = appContext.getString(if (systemAvailable) R.string.installerSystemDescription
                        else R.string.installerSystemUnavailable),
                    icon = systemComponent?.packageName?.let(::loadApplicationIcon),
                    available = systemAvailable
                )
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                add(
                    InstallerOption(
                        selection = InstallerSelection(InstallMethod.SHIZUKU),
                        label = appContext.getString(R.string.installerShizuku),
                        description = appContext.getString(R.string.shizukuReady),
                        icon = ShizukuSupport.managerPackage(appContext)?.let(::loadApplicationIcon)
                    )
                )
            }
        }

        val systemPackage = systemComponent?.packageName
        val external = queryInstallerActivities()
            .asSequence()
            .filter { it.activityInfo.exported }
            .filter { it.activityInfo.packageName != appContext.packageName }
            .filter { it.activityInfo.packageName != systemPackage }
            .filter { info ->
                val permissions = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        info.activityInfo.packageName,
                        PackageManager.GET_PERMISSIONS
                    ).requestedPermissions
                }.getOrNull().orEmpty()
                permissions.any {
                    it == Manifest.permission.REQUEST_INSTALL_PACKAGES ||
                        it == Manifest.permission.INSTALL_PACKAGES
                }
            }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                InstallerOption(
                    selection = InstallerSelection(
                        InstallMethod.EXTERNAL,
                        component.flattenToString()
                    ),
                    label = info.loadLabel(packageManager).toString(),
                    description = info.activityInfo.packageName,
                    icon = loadApplicationIcon(info.activityInfo.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()

        return fixed + external
    }

    fun labelFor(selection: InstallerSelection): String? = when (selection.method) {
        InstallMethod.SYSTEM -> appContext.getString(R.string.installerSystem)
        InstallMethod.SHIZUKU -> appContext.getString(R.string.installerShizuku)
        InstallMethod.EXTERNAL -> ComponentName.unflattenFromString(selection.externalComponent)
            ?.let { component ->
                runCatching {
                    packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
                }.getOrNull()
            }
    }

    @Suppress("DEPRECATION")
    private fun queryInstallerActivities() = packageManager.queryIntentActivities(
        installerIntent(),
        PackageManager.MATCH_DEFAULT_ONLY
    )

    private fun resolveSystemInstaller(): ComponentName? {
        val systemCandidates = queryInstallerActivities().filter { info ->
            val applicationInfo = runCatching {
                packageManager.getApplicationInfo(info.activityInfo.packageName, 0)
            }.getOrNull() ?: return@filter false
            val flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            applicationInfo.flags and flags != 0
        }
        val preferred = listOf("com.google.android.packageinstaller", "com.android.packageinstaller")
        val chosen = preferred.firstNotNullOfOrNull { packageName ->
            systemCandidates.firstOrNull { it.activityInfo.packageName == packageName }
        } ?: systemCandidates.firstOrNull()
        return chosen?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    }

    private fun installerIntent() = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(dummyUri, APK_MIME_TYPE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun loadApplicationIcon(packageName: String): Bitmap? = runCatching {
        packageManager.getApplicationIcon(packageName).toSafeBitmapOrNull(96, 96)
    }.getOrNull()
}
