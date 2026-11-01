package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.extension.toBase64
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RenkinPackStoreTest {

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun equalBitmapPayloadsRestoreIndependentArtwork() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.BLUE)
        }
        val payload = bitmap.toBase64(Bitmap.CompressFormat.PNG, 100)
        val row = DbApplication(
            packageName = "com.example", activityName = "com.example.Main",
            isAdaptiveIcon = false, isXml = false, drawable = payload,
            baseDrawable = payload, baseIsXml = false, baseIsAdaptiveIcon = false
        )
        val restored = store().decodeRow(row, Color.Black)
        val rendered = restored.icon as BitmapIconDrawable
        val base = restored.baseIcon as BitmapIconDrawable
        assertNotSame(rendered, base)
        assertNotSame(rendered.drawable.bitmap, base.drawable.bitmap)
        assertEquals(AndroidColor.BLUE, rendered.drawable.bitmap.getPixel(0, 0))
        rendered.drawable.bitmap.recycle()
        assertEquals(AndroidColor.BLUE, base.drawable.bitmap.getPixel(0, 0))
    }

    private fun store(): RenkinPackStore {
        val context: Application = RuntimeEnvironment.getApplication()
        return RenkinPackStore(context, RenkinPackRepository(context))
    }

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

        val icon = store()
            .decodeRow(row, Color.Black)
            .icon as BitmapIconDrawable

        assertTrue(icon.isAdaptiveIcon())
        assertEquals(ADAPTIVE_ICON_SCALE, icon.previewScale)
    }

    @Test
    fun layeredAdaptiveIconRestoresMaterialYouEditorStateAndOriginalLayers() {
        fun layer(color: Int) = Bitmap.createBitmap(
            AdaptiveIconPackDrawable.LAYER_SIZE,
            AdaptiveIconPackDrawable.LAYER_SIZE,
            Bitmap.Config.ARGB_8888
        ).apply { eraseColor(color) }

        val originalForeground = layer(AndroidColor.WHITE)
        val originalBackground = layer(AndroidColor.BLACK)
        val state = MaterialYouPackEditState(
            selectedScheme = 2,
            customForeground = ColorizerStyle(firstColor = AndroidColor.MAGENTA),
            customBackground = ColorizerStyle(firstColor = AndroidColor.CYAN),
            strokeScale = 1.25f
        )
        val icon = AdaptiveIconPackDrawable(
            layer(AndroidColor.RED).getBytes(Bitmap.CompressFormat.PNG, 100),
            layer(AndroidColor.BLUE).getBytes(Bitmap.CompressFormat.PNG, 100),
            materialYouEditState = state,
            originalForegroundPng = originalForeground.getBytes(Bitmap.CompressFormat.PNG, 100),
            originalBackgroundPng = originalBackground.getBytes(Bitmap.CompressFormat.PNG, 100)
        )
        val row = DbApplication(
            packageName = "com.example",
            activityName = "com.example.Main",
            isAdaptiveIcon = true,
            isXml = true,
            drawable = icon.toDbString()
        )

        val restored = store().decodeRow(row, Color.Black).icon as AdaptiveIconPackDrawable

        assertEquals(state, restored.materialYouEditState)
        assertEquals(AndroidColor.RED, restored.foreground.getPixel(0, 0))
        assertEquals(AndroidColor.BLUE, restored.background.getPixel(0, 0))
        val original = restored.restoreOriginalMaterialYouLayers(state)
        assertEquals(AndroidColor.WHITE, original.foreground.getPixel(0, 0))
        assertEquals(AndroidColor.BLACK, original.background.getPixel(0, 0))
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

        val entry = store()
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

        val entry = store()
            .decodeRow(row, Color.Black)

        assertNull(entry.icon)
        assertTrue(entry.baseIcon is BitmapIconDrawable)
        assertEquals(false, entry.decodeFailed)
    }
}
