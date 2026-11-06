package com.roy233.tvcast.platform.display

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQuadTest {
    @Test
    fun `rectangle needs no perspective correction`() {
        assertEquals(PerspectiveWeights(1f, 1f, 1f, 1f), VideoQuad().perspectiveWeights())
    }

    @Test
    fun `trapezoid receives continuous projective weights`() {
        val weights = VideoQuad(
            topLeft = VideoPoint(0.1f, 0f),
            topRight = VideoPoint(0.9f, 0f),
        ).perspectiveWeights()
        assertEquals(1f, weights.topLeft, 0.0001f)
        assertEquals(1f, weights.topRight, 0.0001f)
        assertEquals(0.8f, weights.bottomRight, 0.0001f)
        assertEquals(0.8f, weights.bottomLeft, 0.0001f)
    }

    @Test
    fun `four by three video is letterboxed within the output quad`() {
        val fitted = VideoQuad().fitInside(4f / 3f, 16f / 9f)
        assertEquals(0.125f, fitted.topLeft.x, 0.0001f)
        assertEquals(0.875f, fitted.topRight.x, 0.0001f)
        assertEquals(0.125f, fitted.bottomLeft.x, 0.0001f)
        assertEquals(0.875f, fitted.bottomRight.x, 0.0001f)
    }
}
