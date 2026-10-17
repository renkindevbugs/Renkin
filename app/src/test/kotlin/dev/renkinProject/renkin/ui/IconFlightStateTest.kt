package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The icon flight between a home row and the edit dialog. These pin the rules the screens rely
 * on: which icons fly, which slots hide while a copy is in the air, and when a return is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconFlightStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val appIcon = ImageBitmap(8, 8)
    private val customIcon = ImageBitmap(8, 8)
    private val slot = Rect(0f, 0f, 40f, 40f)

    private fun placedAnchor(custom: ImageBitmap?, rowShown: () -> Boolean = { true }): IconAnchor {
        val anchor = IconAnchor().apply {
            bitmap = appIcon
            resultBitmap = custom
        }
        compose.setContent {
            if (rowShown()) {
                Row {
                    Box(Modifier.iconAnchor(anchor).size(56.dp))
                    Box(Modifier.deliveryAnchor(anchor).size(56.dp))
                }
            }
        }
        compose.waitForIdle()
        return anchor
    }

    @Test
    fun launchNeedsAPlacedIconWithAPicture() {
        assertNull(IconAnchor().apply { bitmap = appIcon }.launch())

        val anchor = placedAnchor(custom = null)
        anchor.bitmap = null
        assertNull(anchor.launch())
    }

    @Test
    fun aRowWithoutCustomIconSendsOnlyTheAppIcon() {
        val flight = requireNotNull(placedAnchor(custom = null).launch())

        assertNull(flight.arriving)
        assertFalse(flight.newSlotHidden)
        flight.target = slot
        assertFalse(flight.holdsResultSlot)
    }

    @Test
    fun aRowCustomIconFliesIntoNewOnceTheDialogIsMeasured() {
        val flight = requireNotNull(placedAnchor(custom = customIcon).launch())

        assertSame(customIcon, flight.arriving)
        assertTrue(flight.newSlotHidden)
        // The row keeps showing its icons until the dialog draws the flying copies.
        assertFalse(flight.holdsResultSlot)
        assertFalse(flight.inFlight)

        flight.target = slot
        assertTrue(flight.holdsResultSlot)
        assertTrue(flight.inFlight)
    }

    @Test
    fun slotsShowTheirOwnIconsOnlyAfterLanding() {
        val flight = requireNotNull(placedAnchor(custom = customIcon).launch())
        flight.target = slot
        assertFalse(flight.slotVisible)
        assertTrue(flight.tracksSlot)

        flight.phase = IconFlightPhase.LANDED

        assertTrue(flight.slotVisible)
        assertTrue(flight.tracksSlot)
        assertFalse(flight.newSlotHidden)
        assertFalse(flight.holdsResultSlot)
        assertFalse(flight.inFlight)
    }

    @Test
    fun backSendsTheRowsOwnCustomIconHome() {
        val flight = requireNotNull(placedAnchor(custom = customIcon).launch())
        flight.target = slot
        flight.newSlotBounds = slot
        flight.phase = IconFlightPhase.LANDED

        flight.beginReturn()

        assertEquals(IconFlightPhase.RETURNING, flight.phase)
        val delivery = requireNotNull(flight.delivery)
        assertFalse(delivery.applied)
        assertSame(customIcon, delivery.bitmap)
        assertTrue(flight.delivering)
        assertTrue(flight.newSlotHidden)
        assertTrue(flight.holdsResultSlot)
        assertFalse(flight.tracksSlot)
    }

    @Test
    fun applySendsTheNewIconToTheRow() {
        val flight = requireNotNull(placedAnchor(custom = customIcon).launch())
        val applied = BitmapIconDrawable(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        )
        flight.target = slot
        flight.newSlotBounds = slot
        flight.newSlotIcon = applied
        flight.phase = IconFlightPhase.LANDED

        flight.beginReturn(applied = true)

        val delivery = requireNotNull(flight.delivery)
        assertTrue(delivery.applied)
        assertSame(applied, delivery.icon)
    }

    @Test
    fun applyWithAnEmptyNewSlotReturnsOnlyTheAppIcon() {
        val flight = requireNotNull(placedAnchor(custom = null).launch())
        flight.target = slot
        flight.newSlotBounds = slot
        flight.phase = IconFlightPhase.LANDED

        flight.beginReturn(applied = true)

        assertEquals(IconFlightPhase.RETURNING, flight.phase)
        assertNull(flight.delivery)
        assertFalse(flight.holdsResultSlot)
    }

    @Test
    fun aReturnIsSkippedWhenTheRowIsGone() {
        var rowShown by mutableStateOf(true)
        val flight = requireNotNull(placedAnchor(custom = customIcon) { rowShown }.launch())
        flight.phase = IconFlightPhase.LANDED

        rowShown = false
        compose.waitForIdle()
        flight.beginReturn()

        assertEquals(IconFlightPhase.LANDED, flight.phase)
        assertNull(flight.delivery)
    }

    @Test
    fun aFlightReturnsOnlyOnce() {
        val flight = requireNotNull(placedAnchor(custom = null).launch())
        flight.phase = IconFlightPhase.HOME

        flight.beginReturn()

        assertEquals(IconFlightPhase.HOME, flight.phase)
    }
}
