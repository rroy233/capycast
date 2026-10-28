package com.roy233.tvcast.feature.danmaku.presentation

import com.roy233.tvcast.feature.danmaku.domain.MediaClock
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDanmakuTimerTest {

    private var now = 0L
    private val clock = MediaClock { now }

    @Test
    fun addUsesMediaClockInsteadOfWallDelta() {
        clock.durationMs = 100_000L
        clock.start(5_000L)
        val timer = MediaDanmakuTimer(clock)

        assertEquals(5_000L, timer.add(1_234L))
        assertEquals(5_000L, timer.currMillisecond)

        now += 250_000_000L
        assertEquals(250L, timer.add(999_999L))
        assertEquals(5_250L, timer.currMillisecond)
    }

    @Test
    fun seekIsVisibleOnNextAdd() {
        clock.durationMs = 100_000L
        clock.start(1_000L)
        val timer = MediaDanmakuTimer(clock)

        timer.add(0L)
        clock.seek(9_000L)
        timer.add(0L)

        assertEquals(9_000L, timer.currMillisecond)
    }
}
