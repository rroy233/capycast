package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.DEFAULT_PLAYBACK_USER_AGENT
import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话终止原因对应的历史、播放器和界面顺序。 */
class PlaybackSessionLifecycleTest {
    @Test
    fun `every terminal path closes active components exactly once`() {
        val terminalPaths =
            listOf<Pair<String, (PlaybackSessionLifecycle) -> Unit>>(
                "dismiss-error" to { it.dismissError() },
                "user-stop" to { it.stopCasting() },
                "dlna-stop" to { it.stopFromDlna() },
                "non-retained-error" to
                    {
                        it.showError("播放失败", "网络异常", "PLAYBACK_FAILED", true, false)
                    },
                "activity-destroy" to { it.destroy() },
            )

        terminalPaths.forEach { (name, terminate) ->
            val calls = mutableListOf<String>()
            val lifecycle = PlaybackSessionLifecycle(host(calls))
            val context = requireNotNull(lifecycle.beginOpen(request(), 0L))
            lifecycle.completeOpen(context, true)
            calls.clear()

            terminate(lifecycle)

            assertEquals(name, 1, calls.count { it == "closePlaybackComponents" })
        }
    }

    @Test
    fun `same content retry advances attempt without rebuilding components`() {
        val calls = mutableListOf<String>()
        val lifecycle = PlaybackSessionLifecycle(host(calls))
        val first = requireNotNull(lifecycle.beginOpen(request(cid = 10L, url = "first"), 0L))
        lifecycle.completeOpen(first, true)
        val second = requireNotNull(lifecycle.beginOpen(request(cid = 10L, url = "backup"), 5_000L))
        lifecycle.completeOpen(second, true)

        assertEquals(first.token.sessionId, second.token.sessionId)
        assertEquals(first.token.attemptId + 1L, second.token.attemptId)
        assertEquals(listOf("openPlaybackComponents"), calls.filter { "PlaybackComponents" in it })
        assertFalse(lifecycle.acceptEvent(first.token, PlaybackEvent.Ended))
        assertTrue(lifecycle.acceptEvent(second.token, PlaybackEvent.Playing))
    }

    @Test
    fun `new content closes old components before opening new components`() {
        val calls = mutableListOf<String>()
        val lifecycle = PlaybackSessionLifecycle(host(calls))
        val first = requireNotNull(lifecycle.beginOpen(request(cid = 10L), 0L))
        lifecycle.completeOpen(first, true)
        val second = requireNotNull(lifecycle.beginOpen(request(cid = 20L), 0L))
        lifecycle.completeOpen(second, true)

        assertEquals(first.token.sessionId + 1L, second.token.sessionId)
        assertEquals(
            listOf(
                "openPlaybackComponents",
                "closePlaybackComponents",
                "openPlaybackComponents",
            ),
            calls.filter { "PlaybackComponents" in it },
        )
        assertFalse(lifecycle.acceptEvent(first.token, PlaybackEvent.Playing))
    }

    @Test
    fun `user stop cancels resolve and clears media before UI reset`() {
        val calls = mutableListOf<String>()
        val lifecycle = PlaybackSessionLifecycle(host(calls))
        val context = requireNotNull(lifecycle.beginOpen(request(), 0L))
        lifecycle.completeOpen(context, true)
        calls.clear()
        lifecycle.stopCasting()

        assertEquals(
            listOf(
                "cancelResolveJob",
                "finishSessionHistory",
                "resetPendingPlayback",
                "closePlaybackComponents",
                "stopPlaybackForSession",
                "clearSessionIdentity",
                "markCastFailed",
                "showStoppedState",
            ),
            calls.filter { it in STOP_CALLS },
        )
    }

    @Test
    fun `error close stops player before clearing identity`() {
        val calls = mutableListOf<String>()
        PlaybackSessionLifecycle(host(calls))
            .showError("播放失败", "网络异常", "PLAYBACK_FAILED", true, false)

        assertEquals(
            listOf(
                "finishSessionHistory",
                "closePlaybackComponents",
                "stopPlaybackForSession",
                "clearSessionIdentity",
                "runOnMain",
                "showErrorState",
            ),
            calls.filter { it in ERROR_CALLS },
        )
    }

    @Test
    fun `DLNA stop clears playback and schedules its own completion UI`() {
        val calls = mutableListOf<String>()
        PlaybackSessionLifecycle(host(calls)).stopFromDlna()

        assertEquals(
            listOf(
                "clearPendingPause",
                "finishSessionHistory",
                "closePlaybackComponents",
                "stopPlaybackForSession",
                "clearSessionIdentity",
                "runOnMain",
                "showDlnaStoppedState",
            ),
            calls.filter { it in DLNA_CALLS },
        )
    }

    @Test
    fun `Activity destruction closes history before playback components`() {
        val calls = mutableListOf<String>()
        val lifecycle = PlaybackSessionLifecycle(host(calls))
        val context = requireNotNull(lifecycle.beginOpen(request(), 0L))
        lifecycle.completeOpen(context, true)
        calls.clear()
        lifecycle.destroy()

        assertEquals(
            listOf("closeHistory", "closePlaybackComponents"),
            calls.filter { it in DESTROY_CALLS },
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
                    @Suppress("UNCHECKED_CAST") (args!![0] as Function0<Unit>).invoke()
                    null
                }
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
            }
        } as PlaybackSessionHost

    private companion object {
        val STOP_CALLS =
            setOf(
                "cancelResolveJob",
                "finishSessionHistory",
                "resetPendingPlayback",
                "closePlaybackComponents",
                "stopPlaybackForSession",
                "clearSessionIdentity",
                "markCastFailed",
                "showStoppedState",
            )
        val ERROR_CALLS =
            setOf(
                "finishSessionHistory",
                "closePlaybackComponents",
                "stopPlaybackForSession",
                "clearSessionIdentity",
                "runOnMain",
                "showErrorState",
            )
        val DLNA_CALLS =
            setOf(
                "clearPendingPause",
                "finishSessionHistory",
                "closePlaybackComponents",
                "stopPlaybackForSession",
                "clearSessionIdentity",
                "runOnMain",
                "showDlnaStoppedState",
            )
        val DESTROY_CALLS = setOf("closeHistory", "closePlaybackComponents")
    }

    private fun request(
        cid: Long = 10L,
        url: String = "https://example.test/video",
    ): PlaybackRequest {
        val video =
            MediaTrack(
                id = 80,
                codecId = 7,
                width = 1920,
                height = 1080,
                bandwidth = 1_000_000L,
                url = url,
                backupUrls = emptyList(),
            )
        return PlaybackRequest(
            title = "video-$cid",
            bvid = "BV$cid",
            aid = 1L,
            cid = cid,
            durationSec = 60L,
            video = video,
            audio = null,
            progressiveUrl = null,
            videos = listOf(video),
            audios = emptyList(),
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
            actualQuality = 80,
            acceptedQualities = listOf(80),
            qualityLabels = mapOf(80 to "1080P"),
            userAgent = DEFAULT_PLAYBACK_USER_AGENT,
        )
    }
}
