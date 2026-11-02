package ru.solrudev.ackpine.impl.installer.activity

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.solrudev.ackpine.impl.helpers.SessionIdIntents
import ru.solrudev.ackpine.impl.installer.PackageInstallerImpl
import ru.solrudev.ackpine.impl.testutil.TestCompletableProgressSession
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.session.Session
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [23, 33])
class ConfirmationLaunchGuardTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun session() = TestCompletableProgressSession<InstallFailure>(
        UUID.randomUUID(), exceptionalFailureFactory = InstallFailure::Exceptional
    ).also { PackageInstallerImpl.getInstance(app).addSession(it.id, it) }

    private fun wrapper(session: TestCompletableProgressSession<InstallFailure>, target: Intent): Intent =
        Intent(app, SessionBasedInstallConfirmationActivity::class.java)
            .putExtra(Intent.EXTRA_INTENT, target)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 1)
            .also { SessionIdIntents.putSessionId(it, session.id) }

    private fun availableTarget(): Intent = Intent("renkin.test.CONFIRM_INSTALL").also { target ->
        val activity = ActivityInfo().apply {
            packageName = app.packageName
            name = "${app.packageName}.TestConfirmation"
            exported = true
            enabled = true
            applicationInfo = ApplicationInfo(app.applicationInfo)
        }
        shadowOf(app.packageManager).apply {
            addOrUpdateActivity(activity)
            addIntentFilterForActivity(ComponentName(activity.packageName, activity.name),
                IntentFilter(target.action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    @Test
    fun missingConfirmationFailsOnlyItsOwningSessionAndFinishes() {
        shadowOf(app).checkActivities(true)
        val owner = session()
        val unrelated = session()
        val target = Intent().setComponent(ComponentName(app.packageName, "renkin.test.MissingConfirmation"))
        val controller = Robolectric.buildActivity(
            SessionBasedInstallConfirmationActivity::class.java, wrapper(owner, target)
        ).create()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller.get().isFinishing)
        assertTrue(owner.completionException is ActivityNotFoundException)
        assertTrue((owner.state as Session.State.Failed).failure is InstallFailure.Exceptional)
        assertFalse(owner.isCancelled)
        assertEquals(Session.State.Pending, unrelated.state)
        assertNull(unrelated.completionException)
        controller.destroy()
    }

    @Test
    fun availableConfirmationLaunchesWithoutPublishingSuccess() {
        shadowOf(app).checkActivities(true)
        val owner = session()
        val target = availableTarget()
        val controller = Robolectric.buildActivity(
            SessionBasedInstallConfirmationActivity::class.java, wrapper(owner, target)
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(target.action, shadowOf(controller.get()).nextStartedActivityForResult.intent.action)
        assertFalse(controller.get().isFinishing)
        assertNull(owner.completionException)
        assertNull(owner.completedState)
        assertEquals(1, owner.committedNotifiedCount)
        controller.pause().stop().destroy()
    }

    @Test
    fun recreationDoesNotLaunchAnotherConfirmation() {
        shadowOf(app).checkActivities(true)
        val owner = session()
        val controller = Robolectric.buildActivity(
            SessionBasedInstallConfirmationActivity::class.java, wrapper(owner, availableTarget())
        ).setup()
        assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        assertNull(owner.completionException)
        assertNull(owner.completedState)
        assertEquals(1, owner.committedNotifiedCount)
        controller.pause().stop().destroy()
    }

    @Suppress("DEPRECATION")
    @Test
    fun backRemainsAnAbortedSessionRatherThanAnExceptionalFailure() {
        val owner = session()
        val controller = Robolectric.buildActivity(
            SessionBasedInstallConfirmationActivity::class.java, wrapper(owner, availableTarget())
        ).setup()
        controller.get().onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller.get().isFinishing)
        assertTrue((owner.completedState as Session.State.Failed).failure is InstallFailure.Aborted)
        assertNull(owner.completionException)
        controller.pause().stop().destroy()
    }
}
