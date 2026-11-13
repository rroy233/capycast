package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackSessionToken
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证结束事件的历史、会话与弹幕清理时序。 */
class PlaybackEventRouterTest {
    @Test
    fun `Media3 ended clears the session before scheduling UI cleanup`() {
        val calls = mutableListOf<String>()
        val router = PlaybackEventRouter(host(PlaybackBackend.MEDIA3, calls))

        router.dispatch(PlaybackSessionToken(1L, 1L), PlaybackBackend.MEDIA3, PlaybackEvent.Ended)

        assertEquals(
            listOf(
                "finishHistory",
                "setCastEnded",
                "finishUsage",
                "runOnMain",
                "finishPlaybackSession",
                "updateEnded",
            ),
            calls,
        )
    }

    @Test
    fun `VOD source load failure is routed to the VOD coordinator`() {
        val calls = mutableListOf<String>()
        val router = PlaybackEventRouter(host(PlaybackBackend.MEDIA3, calls, isLive = false))

        router.dispatch(
            PlaybackSessionToken(1L, 1L),
            PlaybackBackend.MEDIA3,
            PlaybackEvent.SourceLoadFailed(httpStatus = 403),
        )

        assertEquals(listOf("handleVodSourceLoadFailure"), calls)
    }

    @Test
    fun `live source load failure stays outside VOD recovery`() {
        val calls = mutableListOf<String>()
        val router = PlaybackEventRouter(host(PlaybackBackend.MEDIA3, calls, isLive = true))

        router.dispatch(
            PlaybackSessionToken(1L, 1L),
            PlaybackBackend.MEDIA3,
            PlaybackEvent.SourceLoadFailed(httpStatus = 403),
        )

        assertEquals(emptyList<String>(), calls)
    }

    private fun host(
        backend: PlaybackBackend,
        calls: MutableList<String>,
        isLive: Boolean? = null,
    ): PlaybackEventHost =
        Proxy.newProxyInstance(
            PlaybackEventHost::class.java.classLoader,
            arrayOf(PlaybackEventHost::class.java),
        ) { _, method, args ->
            when (method.name) {
                "activeBackend" -> backend
                "acceptPlaybackEvent" -> true
                "currentPlaybackRequest" -> isLive?.let(::request)
                "runOnMain" -> {
                    calls += method.name
                    @Suppress("UNCHECKED_CAST") (args!![0] as Function0<Unit>).invoke()
                    null
                }
                else -> {
                    calls += method.name
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
        } as PlaybackEventHost

    private fun request(isLive: Boolean): PlaybackRequest {
        val track =
            MediaTrack(
                id = 80,
                codecId = 7,
                width = 1920,
                height = 1080,
                bandwidth = 1_000_000L,
                url = "https://example.test/video",
                backupUrls = emptyList(),
            )
        return PlaybackRequest(
            title = "test",
            bvid = "BV1test",
            aid = 1L,
            cid = 2L,
            durationSec = 60L,
            video = track,
            audio = null,
            progressiveUrl = null,
            videos = listOf(track),
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
            userAgent = "test",
        )
    }
}
