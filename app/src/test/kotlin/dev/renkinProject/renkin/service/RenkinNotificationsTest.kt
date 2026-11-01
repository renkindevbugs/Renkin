package dev.renkinProject.renkin.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [RevokingNotificationManagerShadow::class])
class RenkinNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun allowNotifications() {
        RevokingNotificationManagerShadow.resetPublication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun post() = RenkinNotifications().postIconAvailable(
        context, 42, "test.application", listOf("test.pack"), "Test profile"
    )

    @Test
    @Config(sdk = [23, 26, 33])
    fun allowedPublicationPostsSuggestionAndSummary() {
        post()
        assertEquals(2, RevokingNotificationManagerShadow.attempts)
        assertEquals(2, shadowOf(manager).allNotifications.size)
    }

    @Test fun deniedPermissionDoesNotAttemptPublication() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        post()
        assertEquals(0, RevokingNotificationManagerShadow.attempts)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun permissionRevokedAfterCheckDoesNotEscapeOrPostSummary() {
        RevokingNotificationManagerShadow.failOnAttempt = 1
        post()
        assertEquals(1, RevokingNotificationManagerShadow.attempts)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun summaryRevocationKeepsAlreadyPublishedSuggestionWithoutCrashing() {
        RevokingNotificationManagerShadow.failOnAttempt = 2
        post()
        assertEquals(2, RevokingNotificationManagerShadow.attempts)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Test profile", shadowOf(manager).allNotifications.single().extras.getString(Notification.EXTRA_SUB_TEXT))
    }
}

@Implements(NotificationManager::class)
class RevokingNotificationManagerShadow : ShadowNotificationManager() {
    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification) {
        attempts++
        if (attempts == failOnAttempt) throw SecurityException("Permission revoked during publication")
        super.notify(tag, id, notification)
    }

    companion object {
        var attempts = 0
        var failOnAttempt = Int.MAX_VALUE

        @JvmStatic @Resetter fun resetPublication() {
            attempts = 0
            failOnAttempt = Int.MAX_VALUE
        }
    }
}
