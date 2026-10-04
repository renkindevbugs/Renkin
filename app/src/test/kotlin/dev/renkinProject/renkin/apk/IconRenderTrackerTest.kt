package dev.renkinProject.renkin.apk

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.GlobalApplyGeneratedKey
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconRenderTrackerTest {
    @Test
    fun newerRenderInvalidatesOnlyItsOwnTarget() {
        val tracker = IconRenderTracker()
        val old = tracker.reserve("mail")
        val other = tracker.reserve("browser")
        val newer = tracker.reserve("mail")
        assertFalse(tracker.isCurrent(old))
        assertTrue(tracker.isCurrent(newer))
        assertTrue(tracker.isCurrent(other))
    }

    @Test
    fun resetInvalidatesPendingRenderEvenBeforeAnyNewRequest() {
        val tracker = IconRenderTracker()
        val ticket = tracker.reserve("mail")
        tracker.changed("mail")
        assertFalse(tracker.isCurrent(ticket))
        assertTrue(tracker.isCurrent(tracker.reserve("mail")))
    }

    @Test
    fun globalSaveObservationDoesNotCancelAnExistingRender() {
        val tracker = IconRenderTracker()
        val pending = tracker.reserve("mail")
        val observed = tracker.observe("mail")
        assertTrue(tracker.isCurrent(pending))
        assertTrue(tracker.isCurrent(observed))
        tracker.changed("mail")
        assertFalse(tracker.isCurrent(observed))
    }

    @Test
    fun observationOfUntouchedRowRejectsLaterRequests() {
        val tracker = IconRenderTracker()
        val observed = tracker.observe("mail")
        assertTrue(tracker.isCurrent(observed))
        tracker.reserve("mail")
        assertFalse(tracker.isCurrent(observed))
    }

    @Test
    fun clearingSessionCannotReviveAReservedTicket() {
        val tracker = IconRenderTracker()
        val old = tracker.reserve("mail")
        tracker.clear()
        tracker.reserve("mail")
        assertFalse(tracker.isCurrent(old))
    }

    @Test
    fun calendarPreferencesDoNotInvalidateGenerationButThemedExportDoes() {
        val context = RuntimeEnvironment.getApplication()
        val empty = emptyPreferences()
        val calendar = preferencesOf(CalendarIconsKey to true)
        assertTrue(sameRenderPreferences(empty, calendar, context))
        assertFalse(sameProfilePreferences(empty, calendar))
        assertFalse(sameRenderPreferences(empty, preferencesOf(ExportThemedKey to true), context))
    }

    @Test
    fun effectiveDefaultsAndBuiltBaselinesDoNotInvalidateGeneration() {
        val context = RuntimeEnvironment.getApplication()
        val empty = emptyPreferences()
        val explicit = preferencesOf(GlobalApplyGeneratedKey to true, BuiltPrimarySourceKey to 2)
        assertTrue(sameRenderPreferences(empty, explicit, context))
        assertTrue(sameProfilePreferences(empty, preferencesOf(BuiltPrimarySourceKey to 2)))
    }

    @Test
    fun customRenderIgnoresSourceButRejectsModifierChanges() {
        val empty = emptyPreferences()
        assertTrue(sameCustomRenderPreferences(empty, preferencesOf(PrimarySourceKey to Source.APPLICATION_NAME.ordinal)))
        assertFalse(sameCustomRenderPreferences(empty, preferencesOf(GlobalColorizeKey to true)))
        assertFalse(sameRenderPreferences(empty, preferencesOf(GlobalApplyGeneratedKey to false),
            RuntimeEnvironment.getApplication()))
    }
}
