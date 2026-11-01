package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorFocusComposeTest {
    @get:Rule val compose = createComposeRule()

    private val search = FocusRequester()
    private var editorOpen by mutableStateOf(false)
    private var query by mutableStateOf("")

    private fun showHomeWithSearch() {
        compose.setContent {
            // Stands in for the rest of home: a cleared focus re-enters the first focusable here.
            Box(Modifier.size(1.dp).focusable())
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.testTag("search").focusRequester(search)
            )
            DropFocusWhenEditorOpens(editorOpen)
        }
        compose.runOnIdle { search.requestFocus() }
        compose.onNodeWithTag("search").assertIsFocused()
    }

    @Test fun typingIntoTheSearchKeepsItsFocus() {
        showHomeWithSearch()
        compose.runOnIdle { query = "dro" }
        compose.runOnIdle { query = "droid" }
        compose.onNodeWithTag("search").assertIsFocused()
    }

    @Test fun openingTheEditorDropsTheHomeFocusSoNothingComesBackWhenItCloses() {
        showHomeWithSearch()
        compose.runOnIdle { editorOpen = true }
        compose.onNodeWithTag("search").assertIsNotFocused()

        compose.runOnIdle { editorOpen = false }
        compose.onNodeWithTag("search").assertIsNotFocused()
    }

    @Test fun aFieldTappedWhileTheEditorIsClosingKeepsItsFocusAfterTheEditorIsGone() {
        showHomeWithSearch()
        compose.runOnIdle { editorOpen = true }
        compose.waitForIdle()

        compose.runOnIdle { search.requestFocus() }
        compose.runOnIdle { editorOpen = false }

        compose.onNodeWithTag("search").assertIsFocused()
    }
}
