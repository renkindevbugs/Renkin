package dev.renkinProject.renkin.apk

import android.content.Context
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.shizuku.ShizukuPlugin
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.createSession

class ApkUninstaller(private val context: Context) {
    private val packageUninstaller = PackageUninstaller.getInstance(context)

    /**
     * With [allUsers], a ready Shizuku removes the package for every user. That also drops a record
     * this app cannot see (kept-data uninstall, archive, a copy in another user or profile) which
     * Android still checks a new APK's signer and version against (issue #23).
     */
    suspend fun uninstall(packageName: String, allUsers: Boolean = false): Boolean {
        val viaShizuku = allUsers && ShizukuSupport.state(context) == ShizukuState.READY
        val session = packageUninstaller.createSession(packageName) {
            confirmation = Confirmation.IMMEDIATE
            if (viaShizuku) {
                plugin(ShizukuPlugin::class, ShizukuPlugin.UninstallParameters.Builder().setAllUsers(true).build())
            }
        }
        return session.awaitSucceeded("ApkUninstaller")
    }
}
