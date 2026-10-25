package com.roy233.tvcast.data.bili.mapper

import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.data.bili.BiliApi
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliPlaybackMapperTest {
    @Test
    fun `maps every play info and track field`() {
        val video = track(id = 80, url = "https://video", backup = listOf("https://backup"))
        val audio = track(id = 30280, url = "https://audio", backup = emptyList()).copy(
            isDolbyAudio = true,
        )
        val info = BiliApi.PlayInfo(
            title = "title",
            bvid = "BV1",
            aid = 10,
            cid = 20,
            durationSec = 30,
            video = video,
            audio = audio,
            progressiveUrl = "https://progressive",
            videos = listOf(video),
            audios = listOf(audio),
            cover = "https://cover",
            isLive = true,
            liveFormat = "fmp4",
            liveCdn = "cdn",
            liveBackupCount = 2,
            isPgc = true,
            epId = 40,
            seasonId = 50,
            hasPaid = true,
            isDrm = true,
            isPreview = true,
            actualQuality = 80,
            acceptQualities = listOf(80, 64),
            qualityLabels = mapOf(80 to "蓝光"),
        )

        val expectedTrack = MediaTrack(
            id = 80,
            codecId = 12,
            width = 1920,
            height = 1080,
            bandwidth = 4_000_000,
            url = "https://video",
            backupUrls = listOf("https://backup"),
            isPcdn = true,
            mimeType = "video/mp4",
            codecs = "hev1",
            isHdr = true,
            isDolbyVision = true,
            isDolbyAudio = false,
            initializationRange = "0-100",
            indexRange = "101-200",
            psshBase64 = "cHNzaA==",
        )
        assertEquals(
            PlaybackRequest(
                title = "title",
                bvid = "BV1",
                aid = 10,
                cid = 20,
                durationSec = 30,
                video = expectedTrack,
                audio = expectedTrack.copy(
                    id = 30280,
                    url = "https://audio",
                    backupUrls = emptyList(),
                    isDolbyAudio = true,
                ),
                progressiveUrl = "https://progressive",
                videos = listOf(expectedTrack),
                audios = listOf(expectedTrack.copy(
                    id = 30280,
                    url = "https://audio",
                    backupUrls = emptyList(),
                    isDolbyAudio = true,
                )),
                cover = "https://cover",
                isLive = true,
                liveFormat = "fmp4",
                liveCdn = "cdn",
                liveBackupCount = 2,
                isPgc = true,
                epId = 40,
                seasonId = 50,
                hasPaid = true,
                isDrm = true,
                isPreview = true,
                actualQuality = 80,
                acceptedQualities = listOf(80, 64),
                qualityLabels = mapOf(80 to "蓝光"),
                userAgent = BiliApi.UA,
            ),
            BiliPlaybackMapper.map(info),
        )
    }

    private fun track(id: Int, url: String, backup: List<String>) = BiliApi.Track(
        id = id,
        codecid = 12,
        width = 1920,
        height = 1080,
        bandwidth = 4_000_000,
        url = url,
        backup = backup,
        pcdn = true,
        mimeType = "video/mp4",
        codecs = "hev1",
        isHdr = true,
        isDolbyVision = true,
        initializationRange = "0-100",
        indexRange = "101-200",
        psshBase64 = "cHNzaA==",
    )
}
