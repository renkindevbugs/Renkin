package dev.renkinProject.renkin.ui

import android.app.Application
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.packages.AppCalendarSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorSessionCalendarSourceTest {
    private fun EditorSession.detectCalendar() = apply { applyAppCalendar(AppCalendarSource(application.packageName)) }

    @Test fun lateCalendarDetectionEnablesAppRotationAndRetappingKeepsAnExplicitOptOut() {
        val editor = EditorRecoveryFixtures.editor()
        editor.selectCreateSource(Source.APPLICATION_ICON)
        assertFalse(editor.calendarEnabled)
        editor.detectCalendar()
        assertTrue(editor.calendarEnabled)
        assertEquals(AppCalendarSource.PREFIX, editor.calendarPrefix)
        assertEquals(editor.application.packageName, editor.calendarPackName)
        editor.calendarEnabled = false
        editor.selectCreateSource(Source.APPLICATION_ICON)
        assertFalse(editor.calendarEnabled)
        editor.selectCreateSource(Source.APPLICATION_NAME)
        editor.assertNoCalendar()
    }

    @Test fun uploadAndAnAlternateIconDropRotationAndReturningToCurrentRestoresIt() {
        val editor = EditorRecoveryFixtures.editor().detectCalendar()
        editor.selectCreateSource(Source.APPLICATION_ICON)
        editor.activateUpload(EditorRecoveryFixtures.icon())
        editor.assertNoCalendar()
        editor.selectCreateSource(Source.APPLICATION_ICON)
        assertEquals(IconOrigin.CREATE, editor.draft.origin)
        assertTrue(editor.calendarEnabled)
        editor.selectAlternateIcon("test.editor.Alternate")
        editor.assertNoCalendar()
        editor.selectAlternateIcon("")
        assertTrue(editor.calendarEnabled)
        editor.selectCreateSource(Source.ICON_PACK)
        editor.assertNoCalendar()
    }

    @Test fun switchingFromPackCalendarToAppCalendarUsesTheAppAndNeverTheOldPack() {
        val editor = editorWithPackCalendar().detectCalendar()
        editor.selectCreateSource(Source.APPLICATION_ICON)
        assertEquals(editor.application.packageName, editor.calendarPackName)
        assertEquals(AppCalendarSource.PREFIX, editor.calendarPrefix)
        assertTrue(editor.calendarEnabled)
        editor.applyAppCalendar(null)
        editor.assertNoCalendar()
    }
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
