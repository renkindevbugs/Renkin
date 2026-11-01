package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ExternalIconEditorTest {
    private fun icon() = BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    @Test fun missingHandoffUriDoesNotLaunchAnEditor() = runBlocking {
        assertFalse(openExternalIconEditor(RuntimeEnvironment.getApplication(), icon(), false) { null })
    }

    @Test fun failedHandoffIsReportedWithoutThrowingIntoTheUi() = runBlocking {
        assertFalse(openExternalIconEditor(RuntimeEnvironment.getApplication(), icon(), false) { throw IOException("Disk full") })
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        try {
            openExternalIconEditor(RuntimeEnvironment.getApplication(), icon(), false) { throw CancellationException("Closed editor") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
