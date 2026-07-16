package dev.renkinProject.renkin.apk

import android.app.Application
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.DbApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RenkinPackStoreTest {

    @Test
    fun corruptDrawableOnlyDropsThatIconAndKeepsRowMetadata() {
        val row = DbApplication(
            packageName = "com.example",
            activityName = "com.example.Main",
            isAdaptiveIcon = false,
            isXml = false,
            drawable = "%%%",
            calendarEnabled = true,
            calendarPrefix = "day_",
            calendarPackName = "calendar.pack",
            sourcePackName = "source.pack"
        )

        val entry = RenkinPackStore(RuntimeEnvironment.getApplication())
            .decodeRow(row, Color.Black)

        assertNull(entry.icon)
        assertEquals(true, entry.calendarEnabled)
        assertEquals("day_", entry.calendarPrefix)
        assertEquals("calendar.pack", entry.calendarPackName)
        assertEquals("source.pack", entry.sourcePackName)
        assertSame(row, entry.row)
    }
}
