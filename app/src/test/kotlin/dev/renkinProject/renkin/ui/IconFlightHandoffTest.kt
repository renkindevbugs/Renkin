package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.withIconApply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconFlightHandoffTest {

    @Test fun aReturningOwnIconHandsOverAtOnce() {
        assertTrue(releasesDelivery(applied = false, rowShowsAppliedIcon = false, outcome = null))
    }

    @Test fun anAppliedIconStaysWhileItsApplyIsPendingOrTheRowHasNotDrawnIt() {
        assertFalse(releasesDelivery(applied = true, rowShowsAppliedIcon = false, outcome = null))
        assertFalse(releasesDelivery(applied = true, rowShowsAppliedIcon = false, outcome = IconApplyOutcome.APPLIED))
    }

    @Test fun anAppliedIconHandsOverOnceTheRowDrawsIt() {
        assertTrue(releasesDelivery(applied = true, rowShowsAppliedIcon = true, outcome = IconApplyOutcome.APPLIED))
        assertTrue(releasesDelivery(applied = true, rowShowsAppliedIcon = true, outcome = null))
    }

    @Test fun aFailedApplyHandsTheSlotBackToTheRowsOwnIconAtOnce() {
        val failures = IconApplyOutcome.entries - IconApplyOutcome.APPLIED
        assertEquals(4, failures.size)
        for (outcome in failures) {
            assertTrue(outcome.name, releasesDelivery(applied = true, rowShowsAppliedIcon = false, outcome = outcome))
        }
    }

    @Test fun applyResultsKeepTheirOwnOutcomeAndOnlyTheLatestFew() {
        var results = emptyMap<Long, IconApplyOutcome>()
            .withIconApply(1, IconApplyOutcome.APPLIED)
            .withIconApply(2, IconApplyOutcome.LOCKED)
        assertEquals(mapOf(1L to IconApplyOutcome.APPLIED, 2L to IconApplyOutcome.LOCKED), results)

        for (id in 3L..20L) results = results.withIconApply(id, IconApplyOutcome.APPLIED, keep = 4)
        assertEquals(setOf(17L, 18L, 19L, 20L), results.keys)
    }
}
