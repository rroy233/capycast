package com.roy233.tvcast.data.bili

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VodCdnCoordinatorTest {

    @Test
    fun `tests at most three official candidates concurrently and ranks by ttfb`() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val probed = mutableListOf<String>()
        val urls = listOf(
            "https://cdn-a.bilivideo.com/v.m4s",
            "https://cdn-b.bilivideo.com/v.m4s",
            "https://cdn-c.bilivideo.com/v.m4s",
            "https://cdn-d.bilivideo.com/v.m4s",
            "https://pcdn.szbdyd.com/v.m4s",
        )
        val coordinator = VodCdnCoordinator { url, range ->
            val now = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, now) }
            synchronized(probed) { probed += "$url $range" }
            delay(50L)
            active.decrementAndGet()
            VodProbeResult(
                success = true,
                firstByteMs = when {
                    url.contains("cdn-a") -> 30L
                    url.contains("cdn-b") -> 10L
                    else -> 20L
                },
                throughputBytesPerSecond = 100_000L,
                bytesRead = 4096,
            )
        }

        val routes = coordinator.rankTrack(track(urls.first(), urls.drop(1)))

        assertEquals(3, probed.size)
        assertEquals(3, maxActive.get())
        assertTrue(probed.all { it.endsWith("bytes=0-65535") })
        assertTrue(routes.first().url.contains("cdn-b.bilivideo.com"))
        assertTrue(routes[3].url.contains("cdn-d.bilivideo.com"))
        assertTrue(CdnCandidateSelector.isPcdn(routes.last().url))
    }

    @Test
    fun `uses initialization range for drm media and falls back to a backup route`() = runBlocking {
        val ranges = mutableListOf<String>()
        val coordinator = VodCdnCoordinator { url, range ->
            synchronized(ranges) { ranges += "$url $range" }
            VodProbeResult(
                success = !url.contains("broken"),
                firstByteMs = if (url.contains("broken")) Long.MAX_VALUE else 5L,
                throughputBytesPerSecond = 50_000L,
                bytesRead = if (url.contains("broken")) 0 else 2048,
                failure = if (url.contains("broken")) "HTTP_503" else "",
            )
        }
        val primary = "https://broken.bilivideo.com/v.m4s"
        val fallback = "https://working.bilivideo.com/v.m4s"
        val track = track(primary, listOf(fallback)).copy(initializationRange = "0-1023")

        val routes = coordinator.rankTrack(track)
        val next = coordinator.nextSameRepresentation(
            ordered = routes,
            activeUrl = primary,
            attemptedUrls = setOf(primary),
            allowPcdn = false,
        )

        assertTrue(ranges.all { it.endsWith("bytes=0-1023") })
        assertEquals(fallback, next?.url)
        assertFalse(next?.result?.success == false)
    }

    @Test
    fun `does not switch to the same endpoint or PCDN while an official route remains`() {
        val coordinator = VodCdnCoordinator()
        val primary = "https://upos-a.bilivideo.com/video.m4s?token=one"
        val sameHost = "https://upos-a.bilivideo.com/video.m4s?token=two"
        val official = "https://upos-b.bilivideo.com/video.m4s"
        val pcdn = "https://peer.szbdyd.com/video.m4s"
        val routes = listOf(primary, sameHost, official, pcdn).map(::RankedVodRoute)

        val next = coordinator.nextSameRepresentation(
            ordered = routes,
            activeUrl = primary,
            attemptedUrls = emptySet(),
            allowPcdn = false,
        )
        val afterOfficialFailures = coordinator.nextSameRepresentation(
            ordered = routes,
            activeUrl = primary,
            attemptedUrls = setOf(primary, official),
            allowPcdn = true,
        )

        assertEquals(official, next?.url)
        assertEquals(pcdn, afterOfficialFailures?.url)
    }

    private fun track(url: String, backup: List<String>) = BiliApi.Track(
        id = 120,
        codecid = 12,
        width = 3840,
        height = 2160,
        bandwidth = 8_000_000L,
        url = url,
        backup = backup,
    )
}
