package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentPickerTest {

    @Test
    fun addingTargetKeepsExistingSelection() {
        val selected = listOf(10, 20)

        assertEquals(listOf(10, 20, 30), toggleSegmentTarget(selected, 30))
    }

    @Test
    fun togglingSelectedTargetRemovesOnlyThatTarget() {
        val selected = listOf(10, 20, 30)

        assertEquals(listOf(10, 30), toggleSegmentTarget(selected, 20))
    }
}
