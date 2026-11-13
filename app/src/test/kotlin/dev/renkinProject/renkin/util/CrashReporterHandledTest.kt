package dev.renkinProject.renkin.util

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CrashReporterHandledTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun handledReportIsListedWithoutTriggeringTheLaunchCrashDialog() {
        CrashReporter.recordHandled(context, "Renkin icon pack build report\n\nboom")

        assertEquals(1, CrashReporter.list(context).size)
        assertFalse(CrashReporter.hasNewCrash(context))
    }

    @Test fun handledReportDoesNotHideAnEarlierUnseenCrash() {
        File(context.filesDir, "crashes").apply { mkdirs() }
            .resolve("crash_${System.currentTimeMillis() - 1_000}.txt").writeText("Renkin crash report\n\ncrash")

        CrashReporter.recordHandled(context, "Renkin icon pack build report\n\nboom")

        assertEquals(2, CrashReporter.list(context).size)
        assertTrue(CrashReporter.hasNewCrash(context))
    }
}
