package com.roy233.tvcast.feature.playback.application

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 会话终止原因对应的历史、播放器和界面顺序。 */
class PlaybackSessionLifecycleTest {
    @Test
    fun `user stop cancels resolve and clears media before UI reset`() {
        val calls = mutableListOf<String>()
        PlaybackSessionLifecycle(host(calls)).stopCasting()

        assertEquals(
            listOf(
                "cancelResolveJob", "finishSessionHistory", "clearLiveSession", "resetPendingPlayback",
                "stopPlaybackForSession", "clearSessionIdentity", "markCastFailed", "showStoppedState",
            ),
            calls.filter { it in STOP_CALLS },
        )
    }

    @Test
    fun `error close stops player before clearing identity`() {
        val calls = mutableListOf<String>()
        PlaybackSessionLifecycle(host(calls)).showError("播放失败", "网络异常", "PLAYBACK_FAILED", true, false)

        assertEquals(
            listOf(
                "finishSessionHistory", "clearLiveSession", "stopPlaybackForSession",
                "clearSessionIdentity", "runOnMain", "showErrorState",
            ),
            calls.filter { it in ERROR_CALLS },
        )
    }

    private fun host(calls: MutableList<String>): PlaybackSessionHost =
        Proxy.newProxyInstance(
            PlaybackSessionHost::class.java.classLoader,
            arrayOf(PlaybackSessionHost::class.java),
        ) { _, method, args ->
            calls += method.name
            when (method.name) {
                "modeForLog" -> "PLAYING"
                "isEngineInitialized" -> true
                "runOnMain" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as Function0<Unit>).invoke()
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as PlaybackSessionHost

    private companion object {
        val STOP_CALLS = setOf(
            "cancelResolveJob", "finishSessionHistory", "clearLiveSession", "resetPendingPlayback",
            "stopPlaybackForSession", "clearSessionIdentity", "markCastFailed", "showStoppedState",
        )
        val ERROR_CALLS = setOf(
            "finishSessionHistory", "clearLiveSession", "stopPlaybackForSession",
            "clearSessionIdentity", "runOnMain", "showErrorState",
        )
    }
}
