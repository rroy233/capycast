package com.roy233.tvcast.feature.playback.domain

import com.roy233.tvcast.core.playback.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackRulesTest {

    private fun track(id: Int, codecid: Int, codecs: String = "hvc1") = MediaTrack(
        id = id,
        codecId = codecid,
        width = 3840,
        height = 2160,
        bandwidth = 5_000_000,
        url = "https://example.com/video.m4s",
        backupUrls = emptyList(),
        mimeType = "video/mp4",
        codecs = codecs,
    )

    @Test
    fun `representation key includes every identity field`() {
        assertEquals(
            "125:12:hvc1:video/mp4:3840x2160",
            VodPlaybackRules.representationKey(track(125, 12)),
        )
    }

    @Test
    fun `codec rank prefers hevc then av1 then h264`() {
        assertEquals(3, VodPlaybackRules.videoCodecRank(track(125, 12)))
        assertEquals(2, VodPlaybackRules.videoCodecRank(track(125, 13)))
        assertEquals(1, VodPlaybackRules.videoCodecRank(track(80, 7)))
        assertEquals(0, VodPlaybackRules.videoCodecRank(track(80, 99)))
    }

    @Test
    fun `recovery failure maps to the same stable reasons`() {
        assertEquals(
            "two-severe-rebuffers",
            VodPlaybackRules.recoveryReason(VodRecoveryController.Failure.TWO_SEVERE_REBUFFERS),
        )
        assertEquals(
            "persistent-buffering-5s",
            VodPlaybackRules.recoveryReason(VodRecoveryController.Failure.PERSISTENT_BUFFERING),
        )
        assertEquals(
            "player-open-timeout",
            VodPlaybackRules.recoveryReason(VodRecoveryController.Failure.OPEN_TIMEOUT),
        )
    }

    @Test
    fun `only verified failures consume pcdn candidates`() {
        listOf(
            "player-error",
            "media3-player-error",
            "playback-time-stalled-5s",
            "two-severe-rebuffers",
            "persistent-buffering-5s",
            "player-open-timeout",
            "representation-fallback-open-failed",
        ).forEach { assertTrue(it, VodPlaybackRules.isSubstantiveVodFailure(it)) }
        assertFalse(VodPlaybackRules.isSubstantiveVodFailure("initial-buffering"))
    }
}
