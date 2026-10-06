package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialYouLineWeightTest {
    @Test
    fun centeredLineWeightSlider_placesAndSnapsOneHundredPercentAtCenter() {
        assertEquals(0f, lineWeightToCenteredSlider(0.5f), 0.001f)
        assertEquals(1f, lineWeightToCenteredSlider(1f), 0.001f)
        assertEquals(2f, lineWeightToCenteredSlider(2f), 0.001f)
        assertEquals(0.5f, centeredSliderToLineWeight(0f), 0.001f)
        assertEquals(1f, centeredSliderToLineWeight(1.004f), 0.001f)
        assertEquals(2f, centeredSliderToLineWeight(2f), 0.001f)
    }
}
