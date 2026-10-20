package com.roy233.tvcast.core.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockTest {

    private var now = 0L
    private val clock = MediaClock { now }

    private fun advance(ms: Long) {
        now += ms * 1_000_000L
    }

    @Test
    fun startsFrozenAtZero() {
        assertTrue(clock.isFrozen)
        assertEquals(0L, clock.nowMs())
    }

    @Test
    fun advancesWithMediaTime() {
        clock.durationMs = 100_000L
        clock.start(1_000L)
        advance(500L)
        assertEquals(1_500L, clock.nowMs())
        advance(250L)
        assertEquals(1_750L, clock.nowMs())
    }

    @Test
    fun pauseFreezesPosition() {
        clock.durationMs = 100_000L
        clock.start(1_000L)
        advance(500L)
        clock.pause(1_500L)
        advance(5_000L)
        assertEquals(1_500L, clock.nowMs())
        assertTrue(clock.isFrozen)
    }

    @Test
    fun seekKeepsPauseState() {
        clock.durationMs = 100_000L
        clock.pause(1_000L)
        clock.seek(9_000L)
        assertTrue(clock.isFrozen)
        assertEquals(9_000L, clock.nowMs())
        clock.start(9_000L)
        advance(100L)
        assertEquals(9_100L, clock.nowMs())
    }

    @Test
    fun rateScalesElapsedTime() {
        clock.durationMs = 100_000L
        clock.start(1_000L)
        clock.setRate(2f)
        advance(500L)
        assertEquals(2_000L, clock.nowMs())
    }

    @Test
    fun smallSampleErrorIsIgnored() {
        clock.durationMs = 100_000L
        clock.start(1_000L)
        advance(100L)
        assertFalse(clock.onPlayerSample(1_120L))
        assertEquals(1_100L, clock.nowMs())
    }

    @Test
    fun largeSampleErrorRebases() {
        clock.durationMs = 100_000L
        clock.start(1_000L)
        advance(100L)
        assertTrue(clock.onPlayerSample(5_000L))
        assertEquals(5_000L, clock.nowMs())
    }

    @Test
    fun clampsToDuration() {
        clock.durationMs = 10_000L
        clock.start(9_900L)
        advance(1_000L)
        assertEquals(10_000L, clock.nowMs())
    }
}
