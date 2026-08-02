package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The home list's column count. A phone must keep the single column it always had; wider screens
 * step up at the same breakpoints the rest of the app splits at.
 */
class HomeListColumnsTest {

    @Test
    fun phoneWidths_keepASingleColumn() {
        assertEquals(1, homeListColumns(320))
        assertEquals(1, homeListColumns(411))
        assertEquals(1, homeListColumns(599))
    }

    @Test
    fun theBreakpointsMatchTheSharedWideLayoutThreshold() {
        assertEquals(2, homeListColumns(WIDE_LAYOUT_DP))
        assertEquals(2, homeListColumns(899))
        assertEquals(3, homeListColumns(900))
        assertEquals(3, homeListColumns(1199))
        assertEquals(4, homeListColumns(1200))
    }

    @Test
    fun absurdWidths_stayWithinTheDefinedRange() {
        assertEquals(1, homeListColumns(0))
        assertEquals(4, homeListColumns(4000))
    }
}
