package dev.renkinProject.renkin.apk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

enum class ShizukuState {
    UNSUPPORTED,
    NOT_INSTALLED,
    NOT_RUNNING,
    OUTDATED,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    READY,
    ERROR
}

/** Owns the Shizuku binder/permission checks that Ackpine deliberately leaves to its host app. */
object ShizukuSupport {
    private const val PERMISSION_REQUEST_CODE = 0x524B
    private const val SHEVERY_PERMISSION = "com.hamondev.shevery.permission.PRIVILEGED_API"

    private val MANAGER_PACKAGES = listOf(
        "moe.shizuku.privileged.api",
        "af.shizuku.plus.api",
        "com.hamondev.shevery"
    )

    fun state(context: Context): ShizukuState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ShizukuState.UNSUPPORTED
        return runCatching {
            val binderAlive = Shizuku.pingBinder()
            when {
                // Sui has no manager app but injects the binder, so a live binder wins first.
                !binderAlive && managerPackage(context) == null -> ShizukuState.NOT_INSTALLED
                !binderAlive -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() -> ShizukuState.OUTDATED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() ->
                    ShizukuState.PERMISSION_DENIED
                else -> ShizukuState.PERMISSION_REQUIRED
            }
        }.getOrDefault(ShizukuState.ERROR)
    }

    fun requestPermission(context: Context): Boolean {
        val current = state(context)
        if (current != ShizukuState.PERMISSION_REQUIRED &&
            current != ShizukuState.PERMISSION_DENIED
        ) return false
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    // Permission owners include renamed managers; a launchable manager takes priority over a
    // compatibility stub, which can own the legacy permission without having a UI.
    fun managerPackage(context: Context): String? {
        val packageManager = context.packageManager
        val permissionOwners = listOf(ShizukuProvider.PERMISSION, SHEVERY_PERMISSION).mapNotNull { permission ->
            runCatching { packageManager.getPermissionInfo(permission, 0).packageName }.getOrNull()
        }
        val installedManagers = MANAGER_PACKAGES.filter { name ->
            runCatching { packageManager.getApplicationInfo(name, 0) }.isSuccess
        }
        val candidates = (permissionOwners + installedManagers).distinct()
        return candidates.firstOrNull { name ->
            runCatching { packageManager.getLaunchIntentForPackage(name) != null }.getOrDefault(false)
        } ?: candidates.firstOrNull()
    }

    fun openManager(context: Context): Boolean {
        val intent = managerPackage(context)
            ?.let(context.packageManager::getLaunchIntentForPackage)
            ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    fun observe(onChanged: () -> Unit): AutoCloseable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return AutoCloseable {}
        val mainHandler = Handler(Looper.getMainLooper())
        val notifyChanged: () -> Unit = { mainHandler.post { onChanged() } }
        val binderReceived = Shizuku.OnBinderReceivedListener { notifyChanged() }
        val binderDead = Shizuku.OnBinderDeadListener { notifyChanged() }
        val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST_CODE) notifyChanged()
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        return AutoCloseable {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
    }
}
