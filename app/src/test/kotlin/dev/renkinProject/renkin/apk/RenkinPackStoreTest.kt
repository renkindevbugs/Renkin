package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.extension.toBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RenkinPackStoreTest {

    @Test
    fun adaptiveBitmapRestoresItsPreviewScale() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.BLUE)
        }
        val row = DbApplication(
            packageName = "com.example",
            activityName = "com.example.Main",
            isAdaptiveIcon = true,
            isXml = false,
            drawable = bitmap.toBase64(Bitmap.CompressFormat.PNG, 100)
        )

        val icon = RenkinPackStore(RuntimeEnvironment.getApplication())
            .decodeRow(row, Color.Black)
            .icon as BitmapIconDrawable

        assertTrue(icon.isAdaptiveIcon())
        assertEquals(ADAPTIVE_ICON_SCALE, icon.previewScale)
    }

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
            sourcePackName = "source.pack",
            isCustomIcon = true,
            isLegacyIcon = true
        )

        val entry = RenkinPackStore(RuntimeEnvironment.getApplication())
            .decodeRow(row, Color.Black)

        assertNull(entry.icon)
        assertEquals(true, entry.calendarEnabled)
        assertEquals("day_", entry.calendarPrefix)
        assertEquals("calendar.pack", entry.calendarPackName)
        assertEquals("source.pack", entry.sourcePackName)
        assertEquals(true, entry.isCustom)
        assertEquals(true, entry.isLegacy)
        assertSame(row, entry.row)
        assertTrue(entry.decodeFailed)
    }

    @Test
    fun validBaseRecoversACorruptRenderedDrawable() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.GREEN)
        }
        val row = DbApplication(
            packageName = "com.example",
            activityName = "com.example.Main",
            isAdaptiveIcon = false,
            isXml = false,
            drawable = "%%%",
            baseDrawable = bitmap.toBase64(Bitmap.CompressFormat.PNG, 100),
            baseIsAdaptiveIcon = false,
            baseIsXml = false
        )

        val entry = RenkinPackStore(RuntimeEnvironment.getApplication())
            .decodeRow(row, Color.Black)

        assertNull(entry.icon)
        assertTrue(entry.baseIcon is BitmapIconDrawable)
        assertEquals(false, entry.decodeFailed)
    }
}
