package com.roy233.tvcast.feature.playback.domain

import com.roy233.tvcast.core.playback.PlaybackEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodFrameDropMonitorTest {
    private fun sample(time: Long, rendered: Int, dropped: Int, valid: Boolean = true) =
        PlaybackEvent.VideoFrameSample(time, rendered, dropped, valid)

    @Test
    fun `two severe windows are required and interruption resets the sequence`() {
        val monitor = VodFrameDropMonitor()
        assertNull(monitor.observe(sample(0, 0, 0)))
        for (i in 1..20) {
            val result = monitor.observe(sample(i * 500L, i * 9, i))
            if (i == 20) assertFalse(result!!.sustained)
        }
        monitor.observe(sample(10_500, 180, 20, false))
        monitor.observe(sample(11_000, 180, 20))
        for (i in 1..40) {
            val result = monitor.observe(sample(11_000 + i * 500L, 180 + i * 9, 20 + i))
            if (i == 20) assertFalse(result!!.sustained)
            if (i == 40) assertTrue(result!!.sustained)
        }
    }

    @Test
    fun `small samples and counter resets do not trigger recovery`() {
        val monitor = VodFrameDropMonitor()
        monitor.observe(sample(0, 0, 0))
        for (i in 1..20) {
            val window = monitor.observe(sample(i * 500L, i, i))
            if (i == 20) assertFalse(window!!.severe)
        }
        assertNull(monitor.observe(sample(10_500, 0, 0)))
    }
}
