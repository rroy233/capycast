package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackResolver
import com.roy233.tvcast.feature.playback.model.RankedVodRoute
import com.roy233.tvcast.feature.playback.model.VodAssetRefreshState
import com.roy233.tvcast.feature.playback.model.VodProbeResult
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.feature.playback.routing.VodRouteProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `HTTP 403 refresh is single flight and preserves position and selection`() = runBlocking {
        val currentVideo = track(80, 7, "https://old.example/video")
        val currentAudio = track(30280, 0, "https://old.example/audio")
        val current = request(currentVideo, currentAudio)
        val defaultVideo = track(120, 12, "https://fresh.example/default-video")
        val selectedVideo = track(80, 7, "https://fresh.example/selected-video")
        val defaultAudio = track(30232, 0, "https://fresh.example/default-audio")
        val selectedAudio = track(30280, 0, "https://fresh.example/selected-audio")
        val fresh =
            request(
                video = defaultVideo,
                audio = defaultAudio,
                videos = listOf(defaultVideo, selectedVideo),
                audios = listOf(defaultAudio, selectedAudio),
            )
        val resolution = CompletableDeferred<PlaybackRequest>()
        var resolutionCount = 0
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolveVod(
                    aid: Long,
                    cid: Long,
                    bvid: String,
                    title: String,
                    epId: Long,
                    seasonId: Long,
                ): PlaybackRequest {
                    resolutionCount += 1
                    return resolution.await()
                }
            }
        val host = RecordingVodHost(scope, current, positionMs = 123_456L)
        val coordinator =
            VodPlaybackCoordinator(
                playback = host,
                routeProbe = successfulProbe(),
                resolver = resolver,
                ioDispatcher = Dispatchers.Unconfined,
            )
        coordinator.activeSession =
            VodSession(
                request = current,
                videoRoutes = listOf(RankedVodRoute(currentVideo.url)),
                audioRoutes = listOf(RankedVodRoute(currentAudio.url)),
            )

        assertTrue(coordinator.handleSourceLoadFailure(403))
        assertEquals(VodAssetRefreshState.RESOLVING, coordinator.activeSession?.assetRefreshState)
        assertTrue(coordinator.handleSourceLoadFailure(403))
        assertEquals(1, resolutionCount)

        resolution.complete(fresh)
        withTimeout(2_000L) {
            while (host.opened.isEmpty()) delay(10L)
        }

        val opened = host.opened.single()
        assertEquals(123_456L, opened.second)
        assertEquals(selectedVideo.url, opened.first.video.url)
        assertEquals(selectedAudio.url, opened.first.audio?.url)
        assertEquals(
            VodAssetRefreshState.AWAITING_STABLE,
            coordinator.activeSession?.assetRefreshState,
        )
        assertFalse(coordinator.handleSourceLoadFailure(403))
        assertEquals(1, resolutionCount)
    }

    @Test
    fun `non forbidden load failure does not refresh the playurl`() {
        val request = request(track(80, 7, "https://old.example/video"), null)
        var resolutionCount = 0
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolveVod(
                    aid: Long,
                    cid: Long,
                    bvid: String,
                    title: String,
                    epId: Long,
                    seasonId: Long,
                ): PlaybackRequest {
                    resolutionCount += 1
                    return request
                }
            }
        val host = RecordingVodHost(scope, request, positionMs = 0L)
        val coordinator = VodPlaybackCoordinator(host, successfulProbe(), resolver)
        coordinator.activeSession = VodSession(request, emptyList(), emptyList())

        assertFalse(coordinator.handleSourceLoadFailure(500))
        assertEquals(0, resolutionCount)
    }

    private fun successfulProbe() = VodRouteProbe { _, _ ->
        VodProbeResult(
            success = true,
            firstByteMs = 1L,
            throughputBytesPerSecond = 1_000_000L,
            bytesRead = 1024,
        )
    }

    private class RecordingVodHost(
        override val coroutineScope: CoroutineScope,
        initialRequest: PlaybackRequest,
        private val positionMs: Long,
    ) : VodPlaybackHost {
        private var current = initialRequest
        val opened = mutableListOf<Pair<PlaybackRequest, Long>>()

        override fun showRouteToast(message: String) = Unit

        override fun isPausedWithoutBuffering(): Boolean = false

        override fun currentRequest(): PlaybackRequest = current

        override fun setCurrent(request: PlaybackRequest) {
            current = request
        }

        override fun isCastActive(): Boolean = true

        override fun positionMs(): Long = positionMs

        override fun openPlayback(request: PlaybackRequest, seekToMs: Long): Boolean {
            current = request
            opened += request to seekToMs
            return true
        }
    }

    private fun track(id: Int, codecId: Int, url: String) =
        MediaTrack(
            id = id,
            codecId = codecId,
            width = 1920,
            height = 1080,
            bandwidth = 1_000_000L,
            url = url,
            backupUrls = emptyList(),
        )

    private fun request(
        video: MediaTrack,
        audio: MediaTrack?,
        videos: List<MediaTrack> = listOf(video),
        audios: List<MediaTrack> = listOfNotNull(audio),
    ) =
        PlaybackRequest(
            title = "test",
            bvid = "BV1test",
            aid = 1L,
            cid = 2L,
            durationSec = 600L,
            video = video,
            audio = audio,
            progressiveUrl = null,
            videos = videos,
            audios = audios,
            cover = "",
            isLive = false,
            liveFormat = "",
            liveCdn = "",
            liveBackupCount = 0,
            isPgc = false,
            epId = 0L,
            seasonId = 0L,
            hasPaid = false,
            isDrm = false,
            isPreview = false,
            actualQuality = video.id,
            acceptedQualities = videos.map(MediaTrack::id),
            qualityLabels = emptyMap(),
            userAgent = "test",
        )
}
