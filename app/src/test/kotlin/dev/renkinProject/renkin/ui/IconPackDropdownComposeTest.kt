package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.IconPack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The icon-pack dropdown shows the setting it is given: a change from outside (profile switch,
 * another screen) appears at once, and a pack that is no longer installed is still named.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconPackDropdownComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val packs = listOf(
        IconPack("pack.lawn", "Lawnicons", 1, "1", 0),
        IconPack("pack.mello", "Mello", 1, "1", 0)
    )

    @Test
    fun externalChangeIsShownWithoutANewComposition() {
        var selected by mutableStateOf("pack.lawn")
        compose.setContent {
            IconPackDropdown(R.string.secondaryIconPack, packs, selected, loadIcons = { emptyMap() }) {}
        }
        compose.onNodeWithText("Lawnicons").assertIsDisplayed()

        selected = "pack.mello"

        compose.onNodeWithText("Mello").assertIsDisplayed()
    }

    @Test
    fun uninstalledPackKeepsItsPackageName() {
        compose.setContent {
            IconPackDropdown(R.string.secondaryIconPack, packs, "pack.gone", loadIcons = { emptyMap() }) {}
        }

        compose.onNodeWithText("pack.gone").assertIsDisplayed()
    }

    @Test
    fun pickingAPackReportsItsPackageAndIconsLoadOnce() {
        val picked = mutableListOf<String>()
        var loads = 0
        compose.setContent {
            IconPackDropdown(
                R.string.secondaryIconPack,
                packs,
                packageName = "",
                loadIcons = { loads++; emptyMap() }
            ) { picked += it }
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.none)).performClick()

        compose.onNodeWithText("Mello").performClick()

        assertEquals(listOf("pack.mello"), picked)
        assertEquals(1, loads)
    }
}
