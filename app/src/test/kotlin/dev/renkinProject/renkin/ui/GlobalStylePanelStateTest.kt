package dev.renkinProject.renkin.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalStylePanelStateTest {
    @Test
    fun shadowColourLibraryReturnsThroughShadowBeforeLeavingTheOptions() {
        val navigation = GlobalStylePanelState()
        navigation.open(GlobalStylePanel.SHADOW)
        navigation.open(GlobalStylePanel.SHADOW_COLOR)
        navigation.open(GlobalStylePanel.SAVED_COLORS)

        assertTrue(navigation.back())
        assertEquals(GlobalStylePanel.SHADOW_COLOR, navigation.page)
        assertTrue(navigation.back())
        assertEquals(GlobalStylePanel.SHADOW, navigation.page)
        assertTrue(navigation.back())
        assertEquals(GlobalStylePanel.OPTIONS, navigation.page)
        assertFalse(navigation.back())
    }

    @Test
    fun restoredGradientLibraryKeepsItsColourTargetAndReturnDestination() {
        val navigation = GlobalStylePanelState()
        navigation.open(GlobalStylePanel.OUTLINE_COLOR)
        navigation.open(GlobalStylePanel.GRADIENTS)
        val saved = with(GlobalStylePanelState.Saver) { SaverScope { true }.save(navigation) }

        val restored = requireNotNull(GlobalStylePanelState.Saver.restore(requireNotNull(saved)))

        assertEquals(GlobalStylePanel.GRADIENTS, restored.page)
        assertEquals(GlobalStylePanel.OUTLINE_COLOR, restored.colorTarget)
        assertTrue(restored.back())
        assertEquals(GlobalStylePanel.OUTLINE_COLOR, restored.page)
    }

    @Test
    fun removedInlinePickerStateRestoresToItsColourEditor() {
        val restored = requireNotNull(GlobalStylePanelState.Saver.restore(
            arrayListOf("COLOR_PICKER", "SHADOW_COLOR", "1")
        ))

        assertEquals(GlobalStylePanel.SHADOW_COLOR, restored.page)
        assertTrue(restored.back())
        assertEquals(GlobalStylePanel.SHADOW, restored.page)
    }
}
