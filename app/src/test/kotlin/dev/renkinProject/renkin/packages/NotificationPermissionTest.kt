package dev.renkinProject.renkin.packages

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * POST_NOTIFICATIONS is a runtime permission only from API 33. Gating on it below that would
 * report DENIED forever and silently drop every icon-watch notification, so the pre-33 branch
 * must fall back to the user's notification toggle instead.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(application = Application::class, sdk = [23])
    fun api23_doesNotGateOnTheUngrantablePermission() {
        // POST_NOTIFICATIONS can never be granted here, so gating on it would block every
        // notification — the regression this branch exists to prevent. (The toggle itself goes
        // through AppOps below API 24, which Robolectric's NotificationManager shadow can't
        // move, so only the permission-independence is asserted.)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(canPostNotifications(context))
    }

    @Test
    @Config(application = Application::class, sdk = [32])
    fun api32_followsNotificationToggle_notThePermission() {
        assertTrue(canPostNotifications(context))

        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(false)
        assertFalse(canPostNotifications(context))
    }

    @Test
    @Config(application = Application::class, sdk = [33])
    fun api33_requiresTheRuntimePermission() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(canPostNotifications(context))

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(canPostNotifications(context))
    }

    @Test
    @Config(application = Application::class, sdk = [23])
    fun api23_settingsIntent_isTheAppDetailsPage() {
        val intent = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.data?.schemeSpecificPart)
    }

    @Test
    @Config(application = Application::class, sdk = [33])
    fun api33_settingsIntent_isThePerAppNotificationPage() {
        val intent = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
