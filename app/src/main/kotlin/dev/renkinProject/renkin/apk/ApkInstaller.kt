package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.parameters.Confirmation

enum class ApkInstallResult {
    SUCCESS,
    CONFLICT,
    ABORTED,
    FAILED
}

class ApkInstaller(context: Context) {
    private val packageInstaller = PackageInstaller.getInstance(context)

    suspend fun install(apk: Uri): ApkInstallResult {
        val session = packageInstaller.createSession(apk) {
            confirmation = Confirmation.IMMEDIATE
        }
        return session.awaitInstallResult("ApkInstaller")
    }
}
