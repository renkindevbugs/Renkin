package dev.renkinProject.renkin.apk

import android.content.Context
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.createSession

class ApkUninstaller(context: Context) {
    private val packageUninstaller = PackageUninstaller.getInstance(context)

    suspend fun uninstall(packageName: String): Boolean {
        val session = packageUninstaller.createSession(packageName) {
            confirmation = Confirmation.IMMEDIATE
        }
        return session.awaitSucceeded("ApkUninstaller")
    }
}