package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CenterDialogTest {

    @Test
    fun zeroOffsetRenderProducesAbsoluteCenterInOneCalculation() {
        val offset = centeredIconOffset(
            frameSize = 100,
            contentStart = 20,
            contentSize = 20,
            scale = 0.8f
        )

        assertEquals(0.25f, offset, 0.001f)
    }

    @Test
    fun shiftedPreviewFallbackAddsCorrectionToCurrentOffset() {
        val offset = centeredIconOffset(
            frameSize = 100,
            contentStart = 30,
            contentSize = 20,
            scale = 1f,
            currentOffset = -0.2f
        )

        assertEquals(-0.1f, offset, 0.001f)
    }
}
