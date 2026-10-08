package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainStartupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startupShellNeedsNoHiltViewModelsAndItsControlsAreDisabled() {
        val context: Application = RuntimeEnvironment.getApplication()
        compose.setContent { RenkinTheme { MainStartupScreen() } }
        compose.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.searchApps)).assertIsNotEnabled()
        listOf(R.string.refreshIcons, R.string.openWatchedIcons, R.string.info, R.string.settings).forEach {
            compose.onNodeWithContentDescription(context.getString(it)).assertIsNotEnabled()
        }
    }
}
