package com.roy233.tvcast.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun acceptsEachSupportedSpeedAndFormatsItsLabel() {
        assertEquals(0.75f, PlaybackSpeed.normalize(0.75f))
        assertEquals("1×", PlaybackSpeed.label(1f))
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
    }

    @Test
    fun unsupportedSpeedFallsBackToNormalSpeed() {
        assertEquals(1f, PlaybackSpeed.normalize(1.1f))
        assertEquals("1×", PlaybackSpeed.label(3f))
    }
}
