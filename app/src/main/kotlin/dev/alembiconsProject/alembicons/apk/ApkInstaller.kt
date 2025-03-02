package dev.alembiconsProject.alembicons.apk

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation

class ApkInstaller(context: Context) {
    private val packageInstaller = PackageInstaller.getInstance(context)

    suspend fun install(apk: Uri): Boolean {
        val session = packageInstaller.createSession(apk) {
            confirmation = Confirmation.IMMEDIATE
        }

        return try {
            when (val result = session.await()) {
                is Session.State.Succeeded -> {
                    println("Success")
                    true
                }
                is Session.State.Failed -> {
                    println(result.failure.message)
                    false
                }
            }
        } catch (_: CancellationException) {
            println("Cancelled")
            false
        } catch (exception: Exception) {
            println(exception)
            false
        }
    }
}