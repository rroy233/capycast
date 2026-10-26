package com.roy233.tvcast.feature.playback.routing

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.playback.model.RankedVodPlayback
import com.roy233.tvcast.feature.playback.model.RankedVodRoute
import com.roy233.tvcast.feature.playback.model.VodProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 执行单条点播媒体 Range 探测的外部端口。 */
fun interface VodRouteProbe {
    suspend fun probe(url: String, range: String): VodProbeResult
}

/** 为点播轨道生成 CDN 探测、排序、刷新和故障切换顺序。 */
class VodCdnCoordinator(
    private val probe: VodRouteProbe,
) {
    suspend fun rankPlayback(request: PlaybackRequest): RankedVodPlayback {
        val videoRoutes = rankTrack(request.video)
        val audioRoutes = request.audio?.let { rankTrack(it) }.orEmpty()
        return RankedVodPlayback(
            request = request.copy(
                video = applyRank(request.video, videoRoutes),
                audio = request.audio?.let { applyRank(it, audioRoutes) },
            ),
            videoRoutes = videoRoutes,
            audioRoutes = audioRoutes,
        )
    }

    suspend fun rankTrack(track: MediaTrack): List<RankedVodRoute> {
        val candidates = distinctEndpoints(CdnRouteRules.order(listOf(track.url) + track.backupUrls))
        if (candidates.isEmpty()) return emptyList()
        val official = candidates.filterNot(CdnRouteRules::isPcdn)
        val pcdn = candidates.filter(CdnRouteRules::isPcdn)
        val measured = probeInParallel(track, (official.ifEmpty { pcdn }).take(MAX_PARALLEL_PROBES))
        val measuredUrls = measured.mapTo(mutableSetOf()) { it.url }
        val successful = measured.filter { it.result?.success == true }.sortedWith(ROUTE_ORDER)
        val failed = measured.filter { it.result?.success != true }.sortedWith(ROUTE_ORDER)
        val ranked = if (official.isNotEmpty()) {
            successful.filterNot { CdnRouteRules.isPcdn(it.url) } +
                official.filterNot { it in measuredUrls }.map(::RankedVodRoute) +
                failed.filterNot { CdnRouteRules.isPcdn(it.url) } +
                pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        } else {
            successful + failed + pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        }
        logRanking(track, ranked)
        return ranked.distinctBy { it.url }
    }

    suspend fun refreshBackups(
        track: MediaTrack,
        activeUrl: String,
        excludedUrls: Set<String>,
    ): List<RankedVodRoute> {
        val excluded = (excludedUrls + activeUrl).mapTo(mutableSetOf(CdnRouteRules.routeIdentity(activeUrl))) {
            CdnRouteRules.routeIdentity(it)
        }
        val candidates = distinctEndpoints(CdnRouteRules.order(listOf(track.url) + track.backupUrls))
            .filterNot { CdnRouteRules.routeIdentity(it) in excluded }
        if (candidates.isEmpty()) return emptyList()
        val official = candidates.filterNot(CdnRouteRules::isPcdn)
        val pcdn = candidates.filter(CdnRouteRules::isPcdn)
        val measured = probeInParallel(track, (official.ifEmpty { pcdn }).take(MAX_PARALLEL_PROBES))
            .sortedWith(ROUTE_ORDER)
        val measuredUrls = measured.mapTo(mutableSetOf()) { it.url }
        val ranked = if (official.isNotEmpty()) {
            measured.filterNot { CdnRouteRules.isPcdn(it.url) } +
                official.filterNot { it in measuredUrls }.map(::RankedVodRoute) +
                pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        } else {
            measured + pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        }
        logRanking(track, ranked)
        return ranked
    }

    fun nextSameRepresentation(
        ordered: List<RankedVodRoute>,
        activeUrl: String,
        attemptedUrls: Set<String>,
        allowPcdn: Boolean,
    ): RankedVodRoute? {
        val attempted = (attemptedUrls + activeUrl).mapTo(mutableSetOf()) { CdnRouteRules.routeIdentity(it) }
        val remaining = ordered.filter { CdnRouteRules.routeIdentity(it.url) !in attempted }
        return remaining.firstOrNull { !CdnRouteRules.isPcdn(it.url) }
            ?: remaining.firstOrNull { allowPcdn && CdnRouteRules.isPcdn(it.url) }
    }

    fun applyRoute(track: MediaTrack, route: RankedVodRoute, ordered: List<RankedVodRoute>): MediaTrack =
        track.copy(
            url = route.url,
            backupUrls = ordered.filterNot { it.url == route.url }.map { it.url },
            isPcdn = CdnRouteRules.isPcdn(route.url),
        )

    private suspend fun probeInParallel(track: MediaTrack, urls: List<String>): List<RankedVodRoute> = coroutineScope {
        urls.map { url ->
            async(Dispatchers.IO) {
                val range = track.initializationRange.takeIf(String::isNotBlank)?.let(::normalizeRange) ?: DEFAULT_RANGE
                val result = runCatching { probe.probe(url, range) }
                    .getOrElse { VodProbeResult(false, Long.MAX_VALUE, 0L, 0, it.javaClass.simpleName) }
                RankedVodRoute(url, result)
            }
        }.awaitAll()
    }

    private fun applyRank(track: MediaTrack, routes: List<RankedVodRoute>): MediaTrack =
        routes.firstOrNull()?.let { applyRoute(track, it, routes) } ?: track

    private fun logRanking(track: MediaTrack, routes: List<RankedVodRoute>) {
        LogHub.i(TAG, "rank qn=${track.id} codec=${track.codecId} routes=" + routes.take(12).joinToString(";") {
            "${LogRedaction.host(it.url)}:" + (it.result?.let { result ->
                "${result.firstByteMs}ms,${result.throughputBytesPerSecond}Bps"
            } ?: "unprobed")
        })
    }

    private fun distinctEndpoints(urls: List<String>): List<String> = urls.distinctBy(CdnRouteRules::routeIdentity)

    private companion object {
        const val TAG = "VodCdn"
        const val MAX_PARALLEL_PROBES = 3
        const val PROBE_BYTES = 64 * 1024
        const val DEFAULT_RANGE = "bytes=0-${PROBE_BYTES - 1}"
        val ROUTE_ORDER = compareBy<RankedVodRoute> { it.result?.success != true }
            .thenBy { it.result?.firstByteMs ?: Long.MAX_VALUE }
            .thenByDescending { it.result?.throughputBytesPerSecond ?: 0L }

        fun normalizeRange(raw: String): String? {
            val parts = raw.split('-', limit = 2)
            if (parts.size != 2) return null
            val first = parts[0].toLongOrNull() ?: return null
            val last = parts[1].toLongOrNull() ?: return null
            return "bytes=$first-${minOf(last, first + PROBE_BYTES - 1L)}".takeIf { first >= 0L && last >= first }
        }
    }
}
