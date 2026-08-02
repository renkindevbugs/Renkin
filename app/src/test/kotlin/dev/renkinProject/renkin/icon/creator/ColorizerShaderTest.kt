package dev.renkinProject.renkin.icon.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorizerShaderTest {

    @Test
    fun linearGradientEndpoints_zeroDegreesRunsUpward() {
        val endpoints = linearGradientEndpoints(angle = 0f, width = 100f, height = 60f)

        assertEquals(50f, endpoints.startX, 0.001f)
        assertEquals(60f, endpoints.startY, 0.001f)
        assertEquals(50f, endpoints.endX, 0.001f)
        assertEquals(0f, endpoints.endY, 0.001f)
    }

    @Test
    fun linearGradientEndpoints_ninetyDegreesRunsRight() {
        val endpoints = linearGradientEndpoints(angle = 90f, width = 100f, height = 60f)

        assertEquals(0f, endpoints.startX, 0.001f)
        assertEquals(30f, endpoints.startY, 0.001f)
        assertEquals(100f, endpoints.endX, 0.001f)
        assertEquals(30f, endpoints.endY, 0.001f)
    }
}
