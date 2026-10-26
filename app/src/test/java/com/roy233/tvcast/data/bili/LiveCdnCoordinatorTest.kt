package com.roy233.tvcast.data.bili

import com.roy233.tvcast.feature.playback.domain.LivePlaybackHealth
import com.roy233.tvcast.feature.playback.domain.LivePlaybackStallWatchdog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiveCdnCoordinatorTest {

    @Test
    fun `prefers fmp4 and hevc while keeping PCDN after official routes`() {
        val routes = listOf(
            candidate("https://pcdn-1.szbdyd.com/live.flv", "flv", 7, 0, pcdn = true),
            candidate("https://official-1.bilivideo.com/live.flv", "flv", 12, 1),
            candidate("https://official-2.bilivideo.com/live.m3u8", "fmp4", 7, 2),
            candidate("https://official-3.bilivideo.com/live.m3u8", "fmp4", 12, 3),
        )

        val preferred = LiveCdnCoordinator { LiveProbeResult(true, 10L, 100L, 100) }
            .prefer(routes)

        assertEquals("https://official-3.bilivideo.com/live.m3u8", preferred[0].url)
        assertEquals("https://official-2.bilivideo.com/live.m3u8", preferred[1].url)
        assertTrue(preferred.last().pcdn)
    }

    @Test
    fun `initial probe runs at most three sources concurrently and ranks latency first`() = runBlocking {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val probed = mutableListOf<String>()
        val coordinator = LiveCdnCoordinator { url ->
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, nowActive) }
            synchronized(probed) { probed += url }
            delay(80L)
            active.decrementAndGet()
            val latency = when {
                url.contains("cdn-1") -> 90L
                url.contains("cdn-2") -> 10L
                else -> 30L
            }
            LiveProbeResult(true, latency, 20_000L, 4096)
        }
        val routes = (1..4).map { index ->
            candidate("https://cdn-$index.bilivideo.com/live.m3u8", "fmp4", 12, index - 1)
        } + candidate("https://p2.szbdyd.com/live.flv", "flv", 7, 4, pcdn = true)

        val ranked = coordinator.rankInitial(routes)

        assertEquals(3, probed.size)
        assertEquals(3, maxActive.get())
        assertEquals("cdn-2.bilivideo.com", ranked[0].candidate.cdnLabel)
        assertEquals("cdn-3.bilivideo.com", ranked[1].candidate.cdnLabel)
        assertEquals("cdn-1.bilivideo.com", ranked[2].candidate.cdnLabel)
        assertEquals("cdn-4.bilivideo.com", ranked[3].candidate.cdnLabel)
        assertTrue(ranked.last().candidate.pcdn)
    }

    @Test
    fun `fmp4 hevc preference wins before latency within the same quality`() = runBlocking {
        val routes = listOf(
            candidate("https://avc.bilivideo.com/live.m3u8", "fmp4", 7, 0),
            candidate("https://hevc.bilivideo.com/live.m3u8", "fmp4", 12, 1),
        )
        val coordinator = LiveCdnCoordinator { url ->
            if (url.contains("avc")) {
                LiveProbeResult(true, 10L, 8_000_000L, 4096)
            } else {
                LiveProbeResult(true, 30L, 2_000_000L, 4096)
            }
        }

        val ranked = coordinator.rankInitial(routes)

        assertEquals("hevc.bilivideo.com", ranked.first().candidate.cdnLabel)
        assertEquals(12, ranked.first().candidate.codecid)
    }

    @Test
    fun `PCDN is measured only when there is no official route`() = runBlocking {
        val probed = mutableListOf<String>()
        val coordinator = LiveCdnCoordinator { url ->
            synchronized(probed) { probed += url }
            LiveProbeResult(true, 5L, 500L, 100)
        }
        val pcNodes = listOf(
            candidate("https://p1.szbdyd.com/live.flv", "flv", 7, 0, pcdn = true),
            candidate("https://p2.szbdyd.com/live.flv", "flv", 7, 1, pcdn = true),
        )

        val ranked = coordinator.rankInitial(pcNodes)

        assertEquals(2, probed.size)
        assertTrue(ranked.first().candidate.pcdn)
    }

    @Test
    fun `PCDN is moved ahead of official routes already proven unavailable`() = runBlocking {
        val coordinator = LiveCdnCoordinator { url ->
            if (url.contains("bilivideo.com")) {
                LiveProbeResult(false, Long.MAX_VALUE, 0L, 0, "HTTP 503")
            } else {
                LiveProbeResult(true, 10L, 500L, 100)
            }
        }
        val routes = listOf(
            candidate("https://official-1.bilivideo.com/live.m3u8", "fmp4", 12, 0),
            candidate("https://official-2.bilivideo.com/live.m3u8", "fmp4", 12, 1),
            candidate("https://fallback.szbdyd.com/live.flv", "flv", 7, 2, pcdn = true),
        )

        val ranked = coordinator.rankInitial(routes)

        assertTrue(ranked.first().candidate.pcdn)
        assertEquals("fallback.szbdyd.com", ranked.first().candidate.cdnLabel)
        assertFalse(ranked.last().result?.success ?: true)
    }

    @Test
    fun `empty source list produces no fallback candidate`() = runBlocking {
        val coordinator = LiveCdnCoordinator { LiveProbeResult(true, 1L, 1L, 1) }

        assertTrue(coordinator.rankInitial(emptyList()).isEmpty())
        assertNull(
            coordinator.nextSameQuality(
                ordered = emptyList(),
                quality = 10000,
                attemptedUrls = emptySet(),
            ),
        )
    }

    @Test
    fun `next source skips attempted URLs and preserves same quality constraint`() {
        val coordinator = LiveCdnCoordinator { LiveProbeResult(true, 1L, 1L, 1) }
        val first = candidate("https://first.bilivideo.com/live.m3u8", "fmp4", 12, 0)
        val second = candidate("https://second.bilivideo.com/live.m3u8", "fmp4", 12, 1)
        val differentQuality = candidate("https://other.bilivideo.com/live.m3u8", "fmp4", 12, 2)
            .copy(quality = 400)
        val ranked = listOf(first, second, differentQuality).map(::RankedLiveSource)

        val next = coordinator.nextSameQuality(
            ordered = ranked,
            quality = 10000,
            attemptedUrls = setOf(first.url),
        )

        assertEquals(second.url, next?.candidate?.url)
    }

    @Test
    fun `health asks for a switch after two separate playback rebuffering cycles`() {
        val health = LivePlaybackHealth()
        health.onPlaying()

        assertFalse(health.onBuffering(true))
        assertFalse(health.onBuffering(true))
        assertFalse(health.onBuffering(false))
        assertTrue(health.onBuffering(true))
        assertFalse(health.onBuffering(false))
    }

    @Test
    fun `initial buffering before first Playing does not count toward route switch`() {
        val health = LivePlaybackHealth()

        assertFalse(health.onBuffering(true))
        assertFalse(health.onBuffering(false))
        health.onPlaying()
        assertFalse(health.onBuffering(true))
        assertFalse(health.onBuffering(false))
    }

    @Test
    fun `zero media clock stays healthy while stream counters continue advancing`() {
        val watchdog = LivePlaybackStallWatchdog()

        assertFalse(watchdog.observe(0, 100, 1_000, 0, 0, 1_000, true, false))
        assertFalse(watchdog.observe(0, 100, 2_000, 50, 20, 5_500, true, false))
        assertFalse(watchdog.observe(0, 100, 3_000, 100, 40, 10_000, true, false))
    }

    @Test
    fun `stalled stream triggers once after five seconds and pause resets timeout`() {
        val watchdog = LivePlaybackStallWatchdog()

        assertFalse(watchdog.observe(0, 100, 1_000, 20, 10, 1_000, true, false))
        assertFalse(watchdog.observe(0, 100, 1_000, 20, 10, 5_999, true, false))
        assertTrue(watchdog.observe(0, 100, 1_000, 20, 10, 6_000, true, false))
        assertFalse(watchdog.observe(0, 100, 1_000, 20, 10, 6_500, true, false))

        watchdog.reset()
        assertFalse(watchdog.observe(0, 100, 1_000, 20, 10, 10_000, true, false))
        assertFalse(watchdog.observe(0, 100, 1_000, 20, 10, 20_000, false, false))
        assertFalse(watchdog.observe(0, 100, 1_000, 20, 10, 24_999, true, false))
    }

    private fun candidate(
        url: String,
        format: String,
        codecid: Int,
        index: Int,
        pcdn: Boolean = false,
    ) = LiveStreamCandidate(
        url = url,
        quality = 10000,
        format = format,
        codecName = if (codecid == 12) "HEVC" else "AVC",
        codecid = codecid,
        width = 1920,
        height = 1080,
        bandwidth = 3_000_000L,
        pcdn = pcdn,
        responseIndex = index,
    )
}
