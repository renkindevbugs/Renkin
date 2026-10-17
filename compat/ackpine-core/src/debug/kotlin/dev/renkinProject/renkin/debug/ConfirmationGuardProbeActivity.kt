package dev.renkinProject.renkin.debug

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ru.solrudev.ackpine.impl.helpers.SessionIdIntents
import ru.solrudev.ackpine.impl.installer.PackageInstallerImpl
import ru.solrudev.ackpine.impl.installer.activity.SessionBasedInstallConfirmationActivity
import ru.solrudev.ackpine.impl.testutil.TestCompletableProgressSession
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.session.Session
import java.util.UUID

class ConfirmationGuardProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: TestCompletableProgressSession<InstallFailure>
    private var deadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        session = TestCompletableProgressSession(
            UUID.randomUUID(), exceptionalFailureFactory = InstallFailure::Exceptional
        )
        PackageInstallerImpl.getInstance(this).addSession(session.id, session)
        val missingTarget = Intent().setComponent(
            ComponentName(packageName, "$packageName.debug.DeliberatelyMissingConfirmationActivity")
        )
        val wrapper = Intent(this, SessionBasedInstallConfirmationActivity::class.java)
            .putExtra(Intent.EXTRA_INTENT, missingTarget)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        SessionIdIntents.putSessionId(wrapper, session.id)
        deadline = SystemClock.uptimeMillis() + 5_000
        startActivity(wrapper)
        handler.post(::checkResult)
    }

    private fun checkResult() {
        val exception = session.completionException
        if (exception == null && SystemClock.uptimeMillis() < deadline) {
            handler.postDelayed(::checkResult, 50)
            return
        }
        val passed = exception is ActivityNotFoundException &&
            (session.state as? Session.State.Failed)?.failure is InstallFailure.Exceptional && !session.isCancelled
        val message = if (passed) "PASS: unavailable confirmation ended as an exceptional failure; process survived"
        else "FAIL: expected ActivityNotFoundException, observed ${exception?.javaClass?.simpleName}, ${session.state}"
        Log.i("ConfirmationGuardProbe", message)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
