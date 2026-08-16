package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalApplyProgressTest {

    @Test
    fun activeIconRendering_reportsDeterminateFraction() {
        assertEquals(0.25f, globalApplyProgressFraction(1 to 4))
    }

    @Test
    fun startupAndFinalPersistence_areIndeterminate() {
        assertNull(globalApplyProgressFraction(0 to 0))
        assertNull(globalApplyProgressFraction(4 to 4))
    }

    @Test
    fun idle_hasNoProgressFraction() {
        assertNull(globalApplyProgressFraction(null))
    }
}
