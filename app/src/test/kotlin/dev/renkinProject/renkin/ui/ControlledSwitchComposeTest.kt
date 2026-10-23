package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared switches show their owner's value: a later external change (profile switch,
 * another screen) must appear without a new composition, and a change the owner rejects must
 * not leave the switch visibly flipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlledSwitchComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun externalChangeIsShownWithoutANewComposition() {
        var checked by mutableStateOf(false)
        compose.setContent { DefaultSwitchLayout(checked, R.string.overrideIcon) {} }
        compose.onNode(isToggleable()).assertIsOff()

        checked = true

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun rejectedChangeLeavesTheSwitchAsTheOwnerHasIt() {
        val requests = mutableListOf<Boolean>()
        compose.setContent { DefaultSwitchLayout(false, R.string.overrideIcon) { requests += it } }

        compose.onNode(isToggleable()).performClick()

        assertEquals(listOf(true), requests)
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun infoVariantAlsoFollowsExternalChanges() {
        var checked by mutableStateOf(true)
        compose.setContent {
            DefaultSwitchLayoutWithInfo(checked, R.string.vector, R.string.vectorOptionDescription) { checked = it }
        }
        compose.onNode(isToggleable()).assertIsOn()

        checked = false
        compose.onNode(isToggleable()).assertIsOff()

        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
    }
}
