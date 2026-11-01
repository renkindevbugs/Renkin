package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuProvider

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShizukuSupportTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun installManager(packageName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { this.packageName = packageName }
        )
    }

    private fun declarePermission(permission: String, owner: String) {
        shadowOf(context.packageManager).addPermissionInfo(PermissionInfo().apply {
            name = permission
            packageName = owner
        })
    }

    @Test
    fun withoutBinderOrManagerShizukuIsNotInstalled() {
        assertNull(ShizukuSupport.managerPackage(context))
        assertEquals(ShizukuState.NOT_INSTALLED, ShizukuSupport.state(context))
        assertFalse(ShizukuSupport.openManager(context))
    }

    @Test
    fun installedManagerWithoutBinderIsNotRunning() {
        installManager("moe.shizuku.privileged.api")

        assertEquals("moe.shizuku.privileged.api", ShizukuSupport.managerPackage(context))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuSupport.state(context))
    }

    @Test
    fun shizukuPlusManagerIsRecognised() {
        installManager("af.shizuku.plus.api")

        assertEquals("af.shizuku.plus.api", ShizukuSupport.managerPackage(context))
    }

    @Test
    fun sheveryWithoutLegacyStubIsRecognisedWhileStopped() {
        installManager("com.hamondev.shevery")

        assertEquals("com.hamondev.shevery", ShizukuSupport.managerPackage(context))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuSupport.state(context))
        assertFalse(ShizukuSupport.requestPermission(context))
    }

    @Test
    fun renamedSheveryIsDiscoveredThroughItsNativePermission() {
        val manager = "com.example.renamedmanager"
        installManager(manager)
        declarePermission("com.hamondev.shevery.permission.PRIVILEGED_API", manager)

        assertEquals(manager, ShizukuSupport.managerPackage(context))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuSupport.state(context))
    }

    @Test
    fun openingSheveryPrefersTheManagerOverTheLegacyStub() {
        val manager = "com.hamondev.shevery"
        val stub = "moe.shizuku.privileged.api"
        installManager(stub)
        installManager(manager)
        declarePermission(ShizukuProvider.PERMISSION, stub)
        declarePermission("com.hamondev.shevery.permission.PRIVILEGED_API", manager)
        val launcher = ComponentName(manager, "$manager.MainActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }

        assertEquals(manager, ShizukuSupport.managerPackage(context))
        assertTrue(ShizukuSupport.openManager(context))
        assertEquals(launcher, shadowOf(context).nextStartedActivity.component)
    }

    @Test
    fun manifestRequestsBothNativeSheveryAndLegacyShizukuAccess() {
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty()

        assertTrue("com.hamondev.shevery.permission.PRIVILEGED_API" in permissions)
        assertTrue(ShizukuProvider.PERMISSION in permissions)
    }

    @Test
    fun permissionIsNotRequestedWhileShizukuIsNotRunning() {
        installManager("moe.shizuku.privileged.api")

        assertFalse(ShizukuSupport.requestPermission(context))
    }
}
