package com.roy233.tvcast.platform.player

import com.roy233.tvcast.data.bili.BiliApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackBackendPolicyTest {

    @Test
    fun `routes split DASH VOD through Media3`() {
        assertTrue(VodPlaybackBackendPolicy.usesMedia3(playInfo(progressiveUrl = null)))
    }

    @Test
    fun `keeps progressive VOD on VLC`() {
        assertFalse(VodPlaybackBackendPolicy.usesMedia3(playInfo(progressiveUrl = "https://example.com/video.mp4")))
    }

    @Test
    fun `keeps live streams on VLC even without a progressive URL`() {
        assertFalse(VodPlaybackBackendPolicy.usesMedia3(playInfo(progressiveUrl = null, isLive = true)))
    }

    private fun playInfo(progressiveUrl: String?, isLive: Boolean = false): BiliApi.PlayInfo {
        val video = BiliApi.Track(
            id = 120,
            codecid = 7,
            width = 3840,
            height = 2160,
            bandwidth = 4_000_000L,
            url = "https://example.com/video.m4s",
            backup = emptyList(),
        )
        return BiliApi.PlayInfo(
            title = "test",
            bvid = "BVtest",
            aid = 1L,
            cid = 2L,
            durationSec = 60L,
            video = video,
            audio = null,
            progressiveUrl = progressiveUrl,
            isLive = isLive,
        )
    }
}
