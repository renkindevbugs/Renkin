package dev.renkinProject.renkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallerPromptTest {

    @Test
    fun firstUnconfirmedBuildPromptsForAChoice() {
        assertEquals(
            InstallerChoiceReason.FIRST_CHOICE,
            installerChoiceReason(askEveryTime = false, choiceConfirmed = false)
        )
    }

    @Test
    fun confirmedChoiceSkipsThePrompt() {
        assertNull(installerChoiceReason(askEveryTime = false, choiceConfirmed = true))
    }

    @Test
    fun askEveryTimeAlwaysPrompts() {
        assertEquals(
            InstallerChoiceReason.ASK_EVERY_TIME,
            installerChoiceReason(askEveryTime = true, choiceConfirmed = true)
        )
    }

    @Test
    fun askEveryTimeTakesPrecedenceOverAnUnconfirmedChoice() {
        assertEquals(
            InstallerChoiceReason.ASK_EVERY_TIME,
            installerChoiceReason(askEveryTime = true, choiceConfirmed = false)
        )
    }
}
