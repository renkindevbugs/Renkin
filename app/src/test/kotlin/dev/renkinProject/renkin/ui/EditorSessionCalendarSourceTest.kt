package dev.renkinProject.renkin.ui

import android.app.Application
import dev.renkinProject.renkin.data.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorSessionCalendarSourceTest {
    private fun editorWithPackCalendar() = EditorRecoveryFixtures.editor().apply {
        source = Source.ICON_PACK
        calendarEnabled = true
        calendarPrefix = "bee_calendar_"
        calendarPackName = "bee.pack"
    }

    private fun EditorSession.assertNoCalendar() {
        assertFalse(calendarEnabled)
        assertNull(calendarPrefix)
        assertNull(calendarPackName)
    }

    @Test fun switchingToTextDropsThePackCalendar() {
        editorWithPackCalendar().apply { selectCreateSource(Source.APPLICATION_NAME) }.assertNoCalendar()
    }

    @Test fun switchingToAppIconDropsThePackCalendar() {
        editorWithPackCalendar().apply { selectCreateSource(Source.APPLICATION_ICON) }.assertNoCalendar()
    }

    @Test fun reselectingIconPackKeepsThePackCalendar() {
        val editor = editorWithPackCalendar().apply { selectCreateSource(Source.ICON_PACK) }
        assertTrue(editor.calendarEnabled)
        assertEquals("bee_calendar_", editor.calendarPrefix)
        assertEquals("bee.pack", editor.calendarPackName)
    }
}
