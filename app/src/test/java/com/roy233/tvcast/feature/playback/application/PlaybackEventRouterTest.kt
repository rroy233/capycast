package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEvent
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证结束事件的历史、会话与弹幕清理时序。 */
class PlaybackEventRouterTest {
    @Test
    fun `VLC ended clears the session before scheduling UI cleanup`() {
        val calls = mutableListOf<String>()
        val router = PlaybackEventRouter(host(PlaybackBackend.VLC, calls))

        router.dispatch(PlaybackBackend.VLC, PlaybackEvent.Ended)

        assertEquals(
            listOf(
                "finishHistory", "clearLiveSession", "setCastEnded", "runOnMain",
                "vodDanmakuClose", "liveDanmakuClose", "updateEnded",
            ),
            calls,
        )
    }

    @Test
    fun `Media3 ended closes VOD danmaku and UI in the event callback`() {
        val calls = mutableListOf<String>()
        val router = PlaybackEventRouter(host(PlaybackBackend.MEDIA3, calls))

        router.dispatch(PlaybackBackend.MEDIA3, PlaybackEvent.Ended)

        assertEquals(
            listOf("finishHistory", "clearLiveSession", "setCastEnded", "vodDanmakuClose", "updateEnded"),
            calls,
        )
    }

    private fun host(backend: PlaybackBackend, calls: MutableList<String>): PlaybackEventHost =
        Proxy.newProxyInstance(
            PlaybackEventHost::class.java.classLoader,
            arrayOf(PlaybackEventHost::class.java),
        ) { _, method, args ->
            when (method.name) {
                "activeBackend" -> backend
                "currentPlaybackRequest" -> null
                "runOnMain" -> {
                    calls += method.name
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as Function0<Unit>).invoke()
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
}
