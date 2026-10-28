package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.feature.playback.model.LivePlayInfo
import com.roy233.tvcast.feature.playback.model.LiveProbeResult
import com.roy233.tvcast.feature.playback.model.LiveStreamCandidate
import com.roy233.tvcast.feature.playback.model.RankedLiveSource
import com.roy233.tvcast.feature.playback.routing.LiveRouteProbe
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackCoordinatorTest {
    @Test
    fun `successful live start and route switch activate the room danmaku session`() {
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val host = Proxy.newProxyInstance(
            LivePlaybackHost::class.java.classLoader,
            arrayOf(LivePlaybackHost::class.java),
        ) { _, method, args ->
            when (method.name) {
                "clearVodPendingSwitch" -> Unit
                "setCastDurationMs" -> Unit
                "showPlayback" -> Unit
                "openPlayback" -> { calls += "open"; true }
                "onLivePlaybackOpened" -> { calls += "danmaku:${args[0]}"; Unit }
                "getCoroutineScope" -> scope
                "isPausedWithoutBuffering", "isPlaying" -> false
                "setPendingPause" -> Unit
                "lastTitle" -> "live"
                else -> error("Unexpected host call: ${method.name}")
            }
        } as LivePlaybackHost
        val candidateA = candidate("https://a.example/live", 0)
        val candidateB = candidate("https://b.example/live", 1)
        val routes = listOf(RankedLiveSource(candidateA), RankedLiveSource(candidateB))
        val coordinator = LivePlaybackCoordinator(host, LiveRouteProbe {
            LiveProbeResult(true, 1, 1, 1)
        })

        try {
            coordinator.start(LivePlayInfo(42L, 10000, 10000, listOf(10000), listOf(candidateA, candidateB)), routes, "live")
            assertTrue(coordinator.switchRoute(routes[1], "test-switch", candidateA))
            assertEquals(listOf("open", "danmaku:42", "open", "danmaku:42"), calls)
        } finally {
            coordinator.clear()
            scope.cancel()
        }
    }

    private fun candidate(url: String, index: Int) = LiveStreamCandidate(
        url = url,
        quality = 10000,
        format = "flv",
        codecName = "avc",
        codecid = 7,
        width = 1920,
        height = 1080,
        bandwidth = 1000,
        pcdn = false,
        responseIndex = index,
    )
}
