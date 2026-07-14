package dev.renkinProject.renkin.apk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconCandidateValidationTest {

    @Test
    fun matchingHashKeepsCandidateValid() {
        assertFalse(iconSourceChanged("hash", "hash"))
    }

    @Test
    fun differentHashMarksCandidateAsChanged() {
        assertTrue(iconSourceChanged("old-hash", "new-hash"))
    }

    @Test
    fun missingDrawableMarksStoredCandidateAsChanged() {
        assertTrue(iconSourceChanged("hash", null))
    }

    @Test
    fun lookupWithoutExpectedHashRemainsUnvalidated() {
        assertFalse(iconSourceChanged(null, "current-hash"))
        assertFalse(iconSourceChanged(null, null))
    }
}
