package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackResolver
import com.roy233.tvcast.core.playback.VideoTrackCapability
import com.roy233.tvcast.core.playback.VideoTrackSupport
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

    @Test
    fun `initial and manual selection choose a supported representation`() = runBlocking {
        val avc = track(120, 7, "https://test.example/avc")
        val hevc = track(120, 12, "https://test.example/hevc")
        val current = request(avc, null, listOf(avc, hevc))
        val host = RecordingVodHost(scope, current, positionMs = 0L)
        val coordinator =
            VodPlaybackCoordinator(
                host,
                successfulProbe(),
                unusedResolver(),
                videoCapability =
                    VideoTrackCapability {
                        if (it.codecId == 12) VideoTrackSupport.SUPPORTED
                        else VideoTrackSupport.EXCEEDS_CAPABILITIES
                    },
            )
        assertEquals(hevc, coordinator.selectVideo(current, 120))
        assertEquals(hevc.url, coordinator.rankInitialPlayback(current).request.video.url)
        assertEquals(avc, coordinator.selectVideo(current.copy(isDrm = true), 120))
    }

    @Test
    fun `sustained dropped frames switch only to same quality preserving position`() = runBlocking {
        val avc = track(120, 7, "https://test.example/avc")
        val hevc = track(120, 12, "https://test.example/hevc")
        val lower = track(116, 7, "https://test.example/lower")
        val current = request(avc, null, listOf(avc, hevc, lower))
        val host = RecordingVodHost(scope, current, positionMs = 123_456L)
        val coordinator =
            VodPlaybackCoordinator(
                host,
                successfulProbe(),
                unusedResolver(),
                ioDispatcher = Dispatchers.Unconfined,
                videoCapability = VideoTrackCapability { VideoTrackSupport.SUPPORTED },
            )
        coordinator.activeSession = VodSession(current, emptyList(), emptyList())
        for (i in 0..40) {
            coordinator.observeVideoFrames(PlaybackEvent.VideoFrameSample(i * 500L, i * 9, i, true))
        }
        withTimeout(2_000L) { while (host.opened.isEmpty()) delay(10) }
        assertEquals(hevc.url, host.opened.single().first.video.url)
        assertEquals(123_456L, host.opened.single().second)
    }

    @Test
    fun `sustained dropped frames keep quality and notify once when alternatives are unavailable`() {
        val avc = track(120, 7, "https://test.example/avc")
        val hevc = track(120, 12, "https://test.example/hevc")
        val lower = track(116, 7, "https://test.example/lower")
        val current = request(avc, null, listOf(avc, hevc, lower))
        val host = RecordingVodHost(scope, current, positionMs = 5000L)
        val coordinator =
            VodPlaybackCoordinator(
                host,
                successfulProbe(),
                unusedResolver(),
                videoCapability = VideoTrackCapability { VideoTrackSupport.NO_HARDWARE_DECODER },
            )
        coordinator.activeSession = VodSession(current, emptyList(), emptyList())
        for (i in 0..80) {
            coordinator.observeVideoFrames(PlaybackEvent.VideoFrameSample(i * 500L, i * 9, i, true))
        }
        assertTrue(host.opened.isEmpty())
        assertEquals(1, host.messages.size)
        assertEquals(120, host.currentRequest().video.id)
    }

    @Test
    fun `failed frame recovery restores previous representation and position`() = runBlocking {
        val avc = track(120, 7, "https://test.example/avc")
        val hevc = track(120, 12, "https://test.example/hevc")
        val current = request(avc, null, listOf(avc, hevc))
        val host = RecordingVodHost(scope, current, positionMs = 45_000L)
        host.failNextOpen = true
        val coordinator =
            VodPlaybackCoordinator(
                host,
                successfulProbe(),
                unusedResolver(),
                ioDispatcher = Dispatchers.Unconfined,
                videoCapability = VideoTrackCapability { VideoTrackSupport.SUPPORTED },
            )
        coordinator.activeSession = VodSession(current, emptyList(), emptyList())
        for (i in 0..40) {
            coordinator.observeVideoFrames(PlaybackEvent.VideoFrameSample(i * 500L, i * 9, i, true))
        }
        withTimeout(2_000L) { while (host.opened.size < 2) delay(10) }
        assertEquals(listOf(hevc.url, avc.url), host.opened.map { it.first.video.url })
        assertEquals(45_000L, host.opened.last().second)
        assertEquals(avc, host.currentRequest().video)
    }

    private fun unusedResolver(): PlaybackResolver =
        object : PlaybackResolver {
            override suspend fun resolveVod(
                aid: Long,
                cid: Long,
                bvid: String,
                title: String,
                epId: Long,
                seasonId: Long,
            ): PlaybackRequest = error("unexpected resolve")
        }

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
        val messages = mutableListOf<String>()
        var failNextOpen = false

        override fun showRouteToast(message: String) {
            messages += message
        }

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
            if (failNextOpen) {
                failNextOpen = false
                return false
            }
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
