package com.roy233.tvcast.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 验证播放器事件携带的状态和媒体信息。 */
class PlaybackEventContractTest {

    @Test
    fun `event contract retains state failure and video size payloads`() {
        val cause = IllegalStateException("decoder")
        val events: List<PlaybackEvent> = listOf(
            PlaybackEvent.Playing,
            PlaybackEvent.Paused,
            PlaybackEvent.Stopped,
            PlaybackEvent.Ended,
            PlaybackEvent.Buffering(42.5f),
            PlaybackEvent.Failed("DECODE", "无法解码", cause),
            PlaybackEvent.VideoSizeChanged(3840, 2160),
        )

        assertEquals(42.5f, (events[4] as PlaybackEvent.Buffering).percent)
        val failure = events[5] as PlaybackEvent.Failed
        assertEquals("DECODE", failure.code)
        assertEquals("无法解码", failure.message)
        assertSame(cause, failure.cause)
        assertEquals(3840, (events[6] as PlaybackEvent.VideoSizeChanged).width)
        assertEquals(2160, (events[6] as PlaybackEvent.VideoSizeChanged).height)
    }
}
