package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.UnsavedIconJournal
import dev.renkinProject.renkin.data.UnsavedIconJournalEntry
import dev.renkinProject.renkin.data.UnsavedSessionMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LostUnsavedWorkTest {
    private val offer = UnsavedIconJournal("old", 1, listOf(UnsavedIconJournalEntry("com.a/com.a.Main", null)))
    private val marker = UnsavedSessionMarker("old", 1, editedCount = 1, refreshedCount = 0)

    @Test fun offerOfTheActiveProfileIsRestorable() {
        val work = LostUnsavedWork(marker = null, offer = offer)

        assertEquals(offer, work.restorableOffer(activeProfileId = 1))
        assertTrue(work.hasSomethingToShow(activeProfileId = 1))
    }

    @Test fun offerOfAnotherProfileWaitsAndShowsNothingWithoutAMarker() {
        val work = LostUnsavedWork(marker = null, offer = offer)

        assertNull(work.restorableOffer(activeProfileId = 2))
        assertFalse(work.hasSomethingToShow(activeProfileId = 2))
    }

    @Test fun emptyOfferIsNotRestorable() {
        val work = LostUnsavedWork(marker = null, offer = offer.copy(entries = emptyList()))

        assertNull(work.restorableOffer(activeProfileId = 1))
        assertFalse(work.hasSomethingToShow(activeProfileId = 1))
    }

    @Test fun lostMarkerAloneStillShowsTheIcon() {
        val work = LostUnsavedWork(marker = marker, offer = offer)

        assertTrue(work.hasSomethingToShow(activeProfileId = 2))
    }
}
