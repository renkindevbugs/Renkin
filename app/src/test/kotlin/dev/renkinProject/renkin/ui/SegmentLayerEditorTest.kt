package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentLayerEditorTest {

    private val layers = listOf(
        SegmentLayer(targets = emptyList(), style = ColorizerStyle(firstColor = 1)),
        SegmentLayer(targets = emptyList(), style = ColorizerStyle(firstColor = 2)),
        SegmentLayer(targets = emptyList(), style = ColorizerStyle(firstColor = 3))
    )

    @Test
    fun removingLayerBeforeSelectionKeepsSameLogicalLayerSelected() {
        val result = removeSegmentLayer(layers, selectedIndex = 2, removeIndex = 0)!!

        assertEquals(listOf(layers[1], layers[2]), result.layers)
        assertEquals(1, result.selectedIndex)
    }

    @Test
    fun removingSelectedLastLayerSelectsPreviousLayer() {
        val result = removeSegmentLayer(layers, selectedIndex = 2, removeIndex = 2)!!

        assertEquals(listOf(layers[0], layers[1]), result.layers)
        assertEquals(1, result.selectedIndex)
    }

    @Test
    fun removingLayerAfterSelectionPreservesIndex() {
        val result = removeSegmentLayer(layers, selectedIndex = 0, removeIndex = 2)!!

        assertEquals(listOf(layers[0], layers[1]), result.layers)
        assertEquals(0, result.selectedIndex)
    }

    @Test
    fun removingOnlyLayerIsRejected() {
        assertNull(removeSegmentLayer(layers.take(1), selectedIndex = 0, removeIndex = 0))
    }
}
