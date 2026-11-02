package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SystemInstallerAvailabilityTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val apk = Uri.parse("content://example.provider/pack.apk")

    private fun confirmInstall() = Intent("android.content.pm.action.CONFIRM_INSTALL")
    // The compatibility route still queries this legacy action on supported Android versions.
    @Suppress("DEPRECATION")
    private fun installPackage() = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(apk, APK_MIME_TYPE)

    private fun handler(
        packageName: String,
        system: Boolean = true,
        enabled: Boolean = true,
        exported: Boolean = true
    ) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.InstallStart"
            this.enabled = enabled
            this.exported = exported
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.enabled = true
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
    }

    private fun register(intent: Intent, info: ResolveInfo) {
        val activity = info.activityInfo
        shadowOf(application.packageManager).apply {
            addOrUpdateActivity(activity)
            addIntentFilterForActivity(ComponentName(activity.packageName, activity.name),
                IntentFilter(intent.action).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    intent.scheme?.let(::addDataScheme)
                    intent.type?.let(::addDataType)
                })
        }
    }

    @Test
    fun enabledSystemConfirmationHandlerMakesTheSystemRouteAvailable() {
        register(confirmInstall(), handler("com.android.packageinstaller"))
        assertTrue(hasSystemInstallActivity(application.packageManager, apk))
    }

    @Test
    fun enabledSystemInstallPackageHandlerIsEnoughWithoutConfirmation() {
        register(installPackage(), handler("com.android.packageinstaller"))
        assertTrue(hasSystemInstallActivity(application.packageManager, apk))
        assertTrue(hasSystemInstallActivity(application.packageManager, apk, includeSessionConfirmation = false))
    }

    @Test
    fun thirdPartyInstallersDoNotCountAsThePlatformInstaller() {
        register(confirmInstall(), handler("com.example.installer", system = false))
        register(installPackage(), handler("com.example.installer", system = false))
        assertFalse(hasSystemInstallActivity(application.packageManager, apk))
    }

    @Test
    fun disabledOrUnexportedSystemHandlersAreUnavailable() {
        register(confirmInstall(), handler("com.android.packageinstaller", enabled = false))
        register(installPackage(), handler("com.android.packageinstaller", exported = false))
        assertFalse(hasSystemInstallActivity(application.packageManager, apk))
    }

    @Test
    fun compatibilityCheckIgnoresSessionConfirmationHandlers() {
        register(confirmInstall(), handler("com.android.packageinstaller"))
        assertFalse(hasSystemInstallActivity(application.packageManager, apk, includeSessionConfirmation = false))
    }

    @Test
    fun noHandlerMeansNoSystemInstaller() {
        assertFalse(hasSystemInstallActivity(application.packageManager, apk))
    }
}
