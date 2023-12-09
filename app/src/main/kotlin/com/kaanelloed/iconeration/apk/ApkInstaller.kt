package com.kaanelloed.iconeration.apk

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.SessionResult
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
                is SessionResult.Success -> {
                    println("Success")
                    true
                }
                is SessionResult.Error -> {
                    println(result.cause.message)
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