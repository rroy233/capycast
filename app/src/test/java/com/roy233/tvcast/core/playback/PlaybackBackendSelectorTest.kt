package com.roy233.tvcast.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBackendSelectorTest {

    @Test
    fun routesSplitDashVodThroughMedia3() {
        assertEquals(PlaybackBackend.MEDIA3, PlaybackBackendSelector.select(request(progressiveUrl = null)))
    }

    @Test
    fun keepsProgressiveVodOnVlc() {
        assertEquals(
            PlaybackBackend.VLC,
            PlaybackBackendSelector.select(request(progressiveUrl = "https://example.com/video.mp4")),
        )
    }

    @Test
    fun keepsLiveStreamOnVlcWhenItHasNoProgressiveUrl() {
        assertEquals(PlaybackBackend.VLC, PlaybackBackendSelector.select(request(isLive = true)))
    }

    private fun request(progressiveUrl: String? = null, isLive: Boolean = false): PlaybackRequest {
        val video = MediaTrack(
            id = 80,
            codecId = 7,
            width = 1920,
            height = 1080,
            bandwidth = 4_000_000L,
            url = "https://example.com/video.m4s",
            backupUrls = emptyList(),
        )
        return PlaybackRequest(
            title = "test",
            bvid = "BVtest",
            aid = 1L,
            cid = 2L,
            durationSec = 60L,
            video = video,
            audio = null,
            progressiveUrl = progressiveUrl,
            videos = listOf(video),
            audios = emptyList(),
            cover = "",
            isLive = isLive,
            liveFormat = "",
            liveCdn = "",
            liveBackupCount = 0,
            isPgc = false,
            epId = 0L,
            seasonId = 0L,
            hasPaid = false,
            isDrm = false,
            isPreview = false,
            actualQuality = 80,
            acceptedQualities = listOf(80),
            qualityLabels = emptyMap(),
            userAgent = DEFAULT_PLAYBACK_USER_AGENT,
        )
    }
}
