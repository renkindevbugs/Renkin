package com.kaanelloed.iconeration.apk

import android.content.Context
import kotlinx.coroutines.CancellationException
import ru.solrudev.ackpine.session.SessionResult
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.createSession

class ApkUninstaller(context: Context) {
    private val packageUninstaller = PackageUninstaller.getInstance(context)

    suspend fun uninstall(packageName: String): Boolean {
        val session = packageUninstaller.createSession(packageName) {
            confirmation = Confirmation.IMMEDIATE
        }

        return try {
            when (val result = session.await()) {
                is SessionResult.Success -> {
                    println("Success")
                    true
                }
                is SessionResult.Error -> {
                    println(result.cause)
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