package dev.renkinProject.renkin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSummaryReadinessTest {

    @Test
    fun summaryIsReadyOnlyForLoadedActiveProfileOutsideSwitch() {
        assertFalse(
            isProfileSummaryReady(
                startupComplete = false,
                isProfileSwitching = false,
                baselineProfileId = 2L,
                activeProfileId = 2L
            )
        )
        assertFalse(
            isProfileSummaryReady(
                startupComplete = true,
                isProfileSwitching = true,
                baselineProfileId = 2L,
                activeProfileId = 2L
            )
        )
        assertFalse(
            isProfileSummaryReady(
                startupComplete = true,
                isProfileSwitching = false,
                baselineProfileId = 1L,
                activeProfileId = 2L
            )
        )
        assertTrue(
            isProfileSummaryReady(
                startupComplete = true,
                isProfileSwitching = false,
                baselineProfileId = 2L,
                activeProfileId = 2L
            )
        )
    }
}
