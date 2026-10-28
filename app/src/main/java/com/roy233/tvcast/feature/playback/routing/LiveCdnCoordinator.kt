package com.roy233.tvcast.feature.playback.routing

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.playback.model.LiveProbeResult
import com.roy233.tvcast.feature.playback.model.LiveStreamCandidate
import com.roy233.tvcast.feature.playback.model.RankedLiveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 执行单条直播线路探测的外部端口。 */
fun interface LiveRouteProbe {
    suspend fun probe(url: String): LiveProbeResult
}

/** 按直播候选属性和探测结果生成首播及故障切换顺序。 */
class LiveCdnCoordinator(
    private val probe: LiveRouteProbe,
) {
    suspend fun rankInitial(candidates: List<LiveStreamCandidate>): List<RankedLiveSource> {
        if (candidates.isEmpty()) return emptyList()
        val preferred = prefer(candidates)
        val official = preferred.filterNot { it.pcdn }
        val pcdn = preferred.filter { it.pcdn }
        val measured = probeInParallel((official.ifEmpty { pcdn }).take(MAX_PARALLEL_PROBES))
        val measuredUrls = measured.mapTo(mutableSetOf()) { it.candidate.url }
        val successful = measured.filter { it.result?.success == true }.sortedWith(PROBE_ORDER)
        val failed = measured.filter { it.result?.success != true }.sortedWith(PROBE_ORDER)
        val ranked = if (official.isNotEmpty()) {
            successful.filterNot { it.candidate.pcdn } +
                official.filterNot { it.url in measuredUrls }.map(::RankedLiveSource) +
                successful.filter { it.candidate.pcdn } +
                pcdn.filterNot { it.url in measuredUrls }.map(::RankedLiveSource) +
                failed.filterNot { it.candidate.pcdn } +
                failed.filter { it.candidate.pcdn }
        } else {
            successful + failed + pcdn.filterNot { it.url in measuredUrls }.map(::RankedLiveSource)
        }
        logRanking("initial", ranked)
        return ranked.distinctBy { it.candidate.url }
    }

    suspend fun refreshBackups(
        candidates: List<LiveStreamCandidate>,
        excludedUrls: Set<String>,
    ): List<RankedLiveSource> {
        val ordered = prefer(candidates).filterNot { it.url in excludedUrls }
        if (ordered.isEmpty()) return emptyList()
        val official = ordered.filterNot { it.pcdn }
        val measured = probeInParallel((official.ifEmpty { ordered.filter { it.pcdn } }).take(MAX_PARALLEL_PROBES))
            .sortedWith(PROBE_ORDER)
        logRanking("backup-refresh", measured)
        return measured
    }

    fun prefer(candidates: List<LiveStreamCandidate>): List<LiveStreamCandidate> = candidates
        .distinctBy { it.url }
        .sortedWith(
            compareBy<LiveStreamCandidate> { it.pcdn }
                .thenBy { formatRank(it.format) }
                .thenBy { codecRank(it.codecid) }
                .thenBy { it.responseIndex },
        )

    fun nextSameQuality(
        ordered: List<RankedLiveSource>,
        quality: Int,
        attemptedUrls: Set<String>,
    ): RankedLiveSource? = ordered.firstOrNull {
        it.candidate.quality == quality && it.candidate.url !in attemptedUrls
    }

    private suspend fun probeInParallel(candidates: List<LiveStreamCandidate>): List<RankedLiveSource> =
        coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    val result = runCatching { probe.probe(candidate.url) }.getOrElse { error ->
                        LiveProbeResult(false, Long.MAX_VALUE, 0L, 0, error.javaClass.simpleName)
                    }
                    RankedLiveSource(candidate, result)
                }
            }.awaitAll()
        }

    private fun logRanking(reason: String, routes: List<RankedLiveSource>) {
        LogHub.i(
            TAG,
            "rank reason=$reason routes=${routes.take(10).joinToString(";") { route ->
                val result = route.result
                "${route.candidate.cdnLabel}/${route.candidate.format}/${route.candidate.codecName}/" +
                    "q${route.candidate.quality}/" +
                    (result?.let { "${it.firstByteMs}ms,${it.throughputBytesPerSecond}Bps" } ?: "unprobed")
            }}",
        )
    }

    private companion object {
        const val TAG = "LiveCdn"
        const val MAX_PARALLEL_PROBES = 3
        fun formatRank(format: String): Int = when (format.lowercase()) {
            "fmp4", "mp4", "m4s" -> 0
            "flv" -> 1
            else -> 2
        }
        fun codecRank(codecid: Int): Int = when (codecid) {
            12 -> 0
            7 -> 1
            13 -> 2
            else -> 3
        }
        val PROBE_ORDER = compareBy<RankedLiveSource> { it.result?.success != true }
            .thenBy { formatRank(it.candidate.format) }
            .thenBy { codecRank(it.candidate.codecid) }
            .thenBy { it.result?.firstByteMs ?: Long.MAX_VALUE }
            .thenByDescending { it.result?.throughputBytesPerSecond ?: 0L }
            .thenBy { it.candidate.responseIndex }
    }
}
