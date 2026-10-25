package com.roy233.tvcast.data.bili

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** HTTP 首包与有限 Range 读取结果。 */
data class VodProbeResult(
    val success: Boolean,
    val firstByteMs: Long,
    val throughputBytesPerSecond: Long,
    val bytesRead: Int,
    val failure: String = "",
)

/** 同一视频表示层对应的一条 CDN 与测速结果。 */
data class RankedVodRoute(val url: String, val result: VodProbeResult? = null)

/** 初始排序结果；仅 video 路由触发故障切换，audio 保留各自优选地址。 */
data class RankedVodPlayback(
    val info: BiliApi.PlayInfo,
    val videoRoutes: List<RankedVodRoute>,
    val audioRoutes: List<RankedVodRoute>,
)

/**
 * 点播同画质 CDN 探测和排序。最多并发测试三条非 PCDN 官方候选，线路探测
 * 使用媒体 URL 的 Range 请求；播放质量、编码与表示层保持不变。
 */
class VodCdnCoordinator(
    private val probe: suspend (url: String, range: String) -> VodProbeResult = { url, range ->
        probeUrl(url, range)
    },
) {
    suspend fun rankPlayback(info: BiliApi.PlayInfo): RankedVodPlayback {
        val videoRoutes = rankTrack(info.video)
        val audioRoutes = info.audio?.let { rankTrack(it) }.orEmpty()
        val rankedVideo = applyRank(info.video, videoRoutes)
        val rankedAudio = info.audio?.let { applyRank(it, audioRoutes) }
        val rankedInfo = info.copy(video = rankedVideo, audio = rankedAudio)
        return RankedVodPlayback(rankedInfo, videoRoutes, audioRoutes)
    }

    suspend fun rankTrack(track: BiliApi.Track): List<RankedVodRoute> {
        val candidates = distinctEndpoints(CdnCandidateSelector.order(listOf(track.url) + track.backup))
        if (candidates.isEmpty()) return emptyList()
        val official = candidates.filterNot(CdnCandidateSelector::isPcdn)
        val pcdn = candidates.filter(CdnCandidateSelector::isPcdn)
        val probePool = (official.ifEmpty { pcdn }).take(MAX_PARALLEL_PROBES)
        val measured = probeInParallel(track, probePool)
        val measuredUrls = measured.mapTo(mutableSetOf()) { it.url }
        val successful = measured.filter { it.result?.success == true }.sortedWith(ROUTE_ORDER)
        val failed = measured.filter { it.result?.success != true }.sortedWith(ROUTE_ORDER)
        val result = if (official.isNotEmpty()) {
            successful.filterNot { CdnCandidateSelector.isPcdn(it.url) } +
                official.filterNot { it in measuredUrls }.map(::RankedVodRoute) +
                failed.filterNot { CdnCandidateSelector.isPcdn(it.url) } +
                pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        } else {
            successful + failed + pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        }
        logRanking(track, result)
        return result.distinctBy { it.url }
    }

    /** 后台测速备用源；不探测当前线路，调用方只需更新排序，无需重启播放。 */
    suspend fun refreshBackups(
        track: BiliApi.Track,
        activeUrl: String,
        excludedUrls: Set<String>,
    ): List<RankedVodRoute> {
        val excludedEndpoints = (excludedUrls + activeUrl).mapTo(mutableSetOf()) {
            CdnCandidateSelector.routeIdentity(it)
        }
        val candidates = distinctEndpoints(CdnCandidateSelector.order(listOf(track.url) + track.backup))
            .filterNot { CdnCandidateSelector.routeIdentity(it) in excludedEndpoints }
        if (candidates.isEmpty()) return emptyList()
        val official = candidates.filterNot(CdnCandidateSelector::isPcdn)
        val pcdn = candidates.filter(CdnCandidateSelector::isPcdn)
        val pool = (official.ifEmpty { pcdn }).take(MAX_PARALLEL_PROBES)
        val measured = probeInParallel(track, pool).sortedWith(ROUTE_ORDER)
        val measuredUrls = measured.mapTo(mutableSetOf()) { it.url }
        val unmeasuredOfficial = official.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        val unmeasuredPcdn = pcdn.filterNot { it in measuredUrls }.map(::RankedVodRoute)
        val ordered = if (official.isNotEmpty()) {
            measured.filterNot { CdnCandidateSelector.isPcdn(it.url) } +
                unmeasuredOfficial + unmeasuredPcdn
        } else {
            measured + unmeasuredPcdn
        }
        logRanking(track, ordered)
        return ordered
    }

    fun nextSameRepresentation(
        ordered: List<RankedVodRoute>,
        activeUrl: String,
        attemptedUrls: Set<String>,
        allowPcdn: Boolean,
    ): RankedVodRoute? {
        val attemptedEndpoints = (attemptedUrls + activeUrl).mapTo(mutableSetOf()) {
            CdnCandidateSelector.routeIdentity(it)
        }
        val remaining = ordered.filter { route ->
            CdnCandidateSelector.routeIdentity(route.url) !in attemptedEndpoints
        }
        return remaining.firstOrNull { !CdnCandidateSelector.isPcdn(it.url) }
            ?: remaining.firstOrNull { allowPcdn && CdnCandidateSelector.isPcdn(it.url) }
    }

    fun applyRoute(
        track: BiliApi.Track,
        route: RankedVodRoute,
        ordered: List<RankedVodRoute>,
    ): BiliApi.Track = track.copy(
        url = route.url,
        backup = ordered.filterNot { it.url == route.url }.map { it.url },
        pcdn = CdnCandidateSelector.isPcdn(route.url),
    )

    private suspend fun probeInParallel(track: BiliApi.Track, urls: List<String>): List<RankedVodRoute> =
        coroutineScope {
            urls.map { url ->
                async(Dispatchers.IO) {
                    val range = track.initializationRange.takeIf(String::isNotBlank)
                        ?.let(::normalizeRange) ?: DEFAULT_RANGE
                    val result = runCatching { probe(url, range) }.getOrElse { failure ->
                        VodProbeResult(false, Long.MAX_VALUE, 0L, 0, failure.javaClass.simpleName)
                    }
                    val summary = "probe host=${LogRedaction.host(url)} qn=${track.id} " +
                        "codec=${track.codecid} range=$range success=${result.success} " +
                        "ttfb=${result.firstByteMs}ms throughput=${result.throughputBytesPerSecond}Bps " +
                        "bytes=${result.bytesRead} failure=${result.failure}"
                    if (result.success) LogHub.i(TAG, summary) else LogHub.e(TAG, summary)
                    RankedVodRoute(url, result)
                }
            }.awaitAll()
        }

    private fun applyRank(track: BiliApi.Track, routes: List<RankedVodRoute>): BiliApi.Track {
        val first = routes.firstOrNull() ?: return track
        return applyRoute(track, first, routes)
    }

    private fun logRanking(track: BiliApi.Track, routes: List<RankedVodRoute>) {
        LogHub.i(
            TAG,
            "rank qn=${track.id} codec=${track.codecid} routes=" + routes.take(12).joinToString(";") {
                "${LogRedaction.host(it.url)}:" +
                    (it.result?.let { result -> "${result.firstByteMs}ms,${result.throughputBytesPerSecond}Bps" }
                        ?: "unprobed")
            },
        )
    }

    private fun distinctEndpoints(urls: List<String>): List<String> =
        urls.distinctBy(CdnCandidateSelector::routeIdentity)

    private companion object {
        const val TAG = "VodCdn"
        const val MAX_PARALLEL_PROBES = 3
        const val PROBE_BYTES = 64 * 1024
        const val PROBE_TIMEOUT_SECONDS = 4L
        const val DEFAULT_RANGE = "bytes=0-${PROBE_BYTES - 1}"
        val ROUTE_ORDER = compareBy<RankedVodRoute> { it.result?.success != true }
            .thenBy { it.result?.firstByteMs ?: Long.MAX_VALUE }
            .thenByDescending { it.result?.throughputBytesPerSecond ?: 0L }
        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS + 1L, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun normalizeRange(raw: String): String? {
            val parts = raw.split('-', limit = 2)
            if (parts.size != 2) return null
            val first = parts[0].toLongOrNull() ?: return null
            val last = parts[1].toLongOrNull() ?: return null
            return "bytes=$first-${minOf(last, first + PROBE_BYTES - 1L)}"
                .takeIf { first >= 0L && last >= first }
        }

        fun probeUrl(url: String, range: String): VodProbeResult {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BiliApi.UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Range", range)
                .get()
                .build()
            val started = SystemClock.elapsedRealtime()
            return try {
                client.newCall(request).execute().use { response ->
                    val firstByteMs = SystemClock.elapsedRealtime() - started
                    if (!response.isSuccessful) {
                        return VodProbeResult(false, firstByteMs, 0L, 0, "HTTP_${response.code}")
                    }
                    val input = response.body?.byteStream()
                        ?: return VodProbeResult(false, firstByteMs, 0L, 0, "empty_body")
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (total < PROBE_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, PROBE_BYTES - total))
                        if (count < 0) break
                        total += count
                    }
                    val elapsedMs = (SystemClock.elapsedRealtime() - started - firstByteMs).coerceAtLeast(1L)
                    val throughput = total * 1_000L / elapsedMs
                    VodProbeResult(
                        success = total > 0,
                        firstByteMs = firstByteMs,
                        throughputBytesPerSecond = throughput,
                        bytesRead = total,
                        failure = if (total > 0) "" else "empty_range",
                    )
                }
            } catch (failure: Exception) {
                VodProbeResult(false, Long.MAX_VALUE, 0L, 0, failure.javaClass.simpleName)
            }
        }
    }
}
