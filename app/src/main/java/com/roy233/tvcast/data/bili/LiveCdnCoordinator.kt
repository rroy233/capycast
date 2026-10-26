package com.roy233.tvcast.data.bili

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** 单条直播线路的 HTTP 首包与短范围读取测速结果。 */
data class LiveProbeResult(
    val success: Boolean,
    val firstByteMs: Long,
    val throughputBytesPerSecond: Long,
    val bytesRead: Int,
    val failure: String = "",
)

/** 附带测速结果的直播源排序项；未测速线路的 result 为空。 */
data class RankedLiveSource(
    val candidate: LiveStreamCandidate,
    val result: LiveProbeResult? = null,
)

/**
 * 直播线路探测与排序策略。
 *
 * 首播最多并行探测三条非 PCDN 官方线路。测速按首包延迟优先，吞吐只用于延迟
 * 相同或接近时的决胜；未探测的官方线路仍排在 PCDN 前面，供故障切源继续尝试。
 */
class LiveCdnCoordinator(
    private val probeSource: suspend (String) -> LiveProbeResult = { url -> probeUrl(url) },
) {
    /** 探测最优首播线路，并返回包括未探测备用源在内的完整故障切换顺序。 */
    suspend fun rankInitial(candidates: List<LiveStreamCandidate>): List<RankedLiveSource> {
        if (candidates.isEmpty()) return emptyList()
        val preferred = prefer(candidates)
        val official = preferred.filterNot { it.pcdn }
        val pcNodes = preferred.filter { it.pcdn }
        val probePool = official.ifEmpty { pcNodes }
        val targets = probePool.take(MAX_PARALLEL_PROBES)
        val measured = probeInParallel(targets)
        val successful = measured.filter { it.result?.success == true }.sortedWith(PROBE_ORDER)
        val failed = measured.filter { it.result?.success != true }.sortedWith(PROBE_ORDER)
        val measuredUrls = measured.mapTo(mutableSetOf()) { it.candidate.url }

        val result = if (official.isNotEmpty()) {
            val successfulOfficial = successful.filterNot { it.candidate.pcdn }
            val failedOfficial = failed.filterNot { it.candidate.pcdn }
            val successfulPcdn = successful.filter { it.candidate.pcdn }
            val failedPcdn = failed.filter { it.candidate.pcdn }
            val unmeasuredOfficial = official.filterNot { it.url in measuredUrls }
                .map { RankedLiveSource(it) }
            val unmeasuredPcdn = pcNodes.filterNot { it.url in measuredUrls }
                .map { RankedLiveSource(it) }
            successfulOfficial + unmeasuredOfficial + successfulPcdn +
                unmeasuredPcdn + failedOfficial + failedPcdn
        } else {
            successful + failed + pcNodes.filterNot { it.url in measuredUrls }
                .map { RankedLiveSource(it) }
        }
        logRanking("initial", result)
        return result.distinctBy { it.candidate.url }
    }

    /** 后台刷新尚未使用的备用线路；不探测当前活动源，也不会主动切换播放。 */
    suspend fun refreshBackups(
        candidates: List<LiveStreamCandidate>,
        excludedUrls: Set<String>,
    ): List<RankedLiveSource> {
        val ordered = prefer(candidates).filterNot { it.url in excludedUrls }
        if (ordered.isEmpty()) return emptyList()
        val official = ordered.filterNot { it.pcdn }
        val pool = (official.ifEmpty { ordered.filter { it.pcdn } })
            .take(MAX_PARALLEL_PROBES)
        val measured = probeInParallel(pool).sortedWith(PROBE_ORDER)
        logRanking("backup-refresh", measured)
        return measured
    }

    /** 按容器和视频编码偏好整理线路，并保留相同优先级下的官方响应顺序。 */
    fun prefer(candidates: List<LiveStreamCandidate>): List<LiveStreamCandidate> = candidates
        .distinctBy { it.url }
        .sortedWith(
            compareBy<LiveStreamCandidate> { it.pcdn }
                .thenBy { formatRank(it.format) }
                .thenBy { codecRank(it.codecid) }
                .thenBy { it.responseIndex },
        )

    /** 返回给定失败线路之后、尚未尝试且画质相同的下一条线路。 */
    fun nextSameQuality(
        ordered: List<RankedLiveSource>,
        quality: Int,
        attemptedUrls: Set<String>,
    ): RankedLiveSource? = ordered.firstOrNull { route ->
        route.candidate.quality == quality && route.candidate.url !in attemptedUrls
    }

    private suspend fun probeInParallel(
        candidates: List<LiveStreamCandidate>,
    ): List<RankedLiveSource> = coroutineScope {
        candidates.map { candidate ->
            async(Dispatchers.IO) {
                val result = runCatching { probeSource(candidate.url) }
                    .getOrElse { failure ->
                        LiveProbeResult(
                            success = false,
                            firstByteMs = Long.MAX_VALUE,
                            throughputBytesPerSecond = 0L,
                            bytesRead = 0,
                            failure = failure.javaClass.simpleName,
                        )
                    }
                val message = "probe host=${candidate.cdnLabel} format=${candidate.format} " +
                    "codec=${candidate.codecName} success=${result.success} " +
                    "firstByte=${result.firstByteMs}ms " +
                    "throughput=${result.throughputBytesPerSecond}Bps " +
                    "bytes=${result.bytesRead} failure=${result.failure}"
                if (result.success) LogHub.i(TAG, message) else LogHub.e(TAG, message)
                RankedLiveSource(candidate, result)
            }
        }.awaitAll()
    }

    private fun logRanking(reason: String, sources: List<RankedLiveSource>) {
        LogHub.i(
            TAG,
            "rank reason=$reason routes=${sources.take(10).joinToString(";") { route ->
                val probe = route.result
                "${route.candidate.cdnLabel}/${route.candidate.format}/" +
                    "${route.candidate.codecName}/q${route.candidate.quality}/" +
                    "${if (probe == null) "unprobed" else "${probe.firstByteMs}ms,${probe.throughputBytesPerSecond}Bps"}"
            }}",
        )
    }

    private companion object {
        const val TAG = "LiveCdn"
        const val MAX_PARALLEL_PROBES = 3
        const val RANGE_BYTES = 64 * 1024
        const val MAX_PLAYLIST_HOPS = 3
        const val PROBE_TIMEOUT_SECONDS = 4L
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS + 2L, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        fun formatRank(format: String): Int = when (format.lowercase()) {
            "fmp4", "mp4", "m4s" -> 0
            "flv" -> 1
            else -> 2
        }
        fun codecRank(codecid: Int): Int = when (codecid) {
            12 -> 0 // HEVC first when TV hardware decoder support is available.
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

        suspend fun probeUrl(rawUrl: String): LiveProbeResult = withContext(Dispatchers.IO) {
            var targetUrl = rawUrl
            var firstByteMs: Long? = null
            var lastResponse: RangeRead? = null
            repeat(MAX_PLAYLIST_HOPS) {
                val url = targetUrl.toHttpUrlOrNull()
                    ?: return@withContext failedProbe("invalid playlist URL").toProbeResult()
                val response = readRange(url.toString())
                lastResponse = response
                if (firstByteMs == null) firstByteMs = response.firstByteMs
                if (!response.success) {
                    return@withContext response.toProbeResult(
                        firstByteMs = firstByteMs ?: response.firstByteMs,
                    )
                }
                val playlist = response.contentType.contains("mpegurl", ignoreCase = true) ||
                    url.encodedPath.endsWith(".m3u8", ignoreCase = true)
                if (!playlist) {
                    return@withContext response.toProbeResult(
                        firstByteMs = firstByteMs ?: response.firstByteMs,
                    )
                }
                val child = firstPlaylistUri(response.bytes)
                    ?: return@withContext response.toProbeResult(
                        firstByteMs = firstByteMs ?: response.firstByteMs,
                    )
                targetUrl = runCatching { URI(url.toString()).resolve(child).toString() }
                    .getOrElse {
                        return@withContext response.toProbeResult(
                            firstByteMs = firstByteMs ?: response.firstByteMs,
                        )
                    }
            }
            lastResponse?.toProbeResult(firstByteMs = firstByteMs ?: Long.MAX_VALUE)
                ?: failedProbe("empty playlist response").toProbeResult()
        }

        private data class RangeRead(
            val success: Boolean,
            val firstByteMs: Long,
            val throughputBytesPerSecond: Long,
            val bytes: ByteArray,
            val contentType: String = "",
            val failure: String = "",
        ) {
            fun toProbeResult(
                success: Boolean = this.success,
                bytesRead: Int = bytes.size,
                throughputBytesPerSecond: Long = this.throughputBytesPerSecond,
                failure: String = this.failure,
                firstByteMs: Long = this.firstByteMs,
            ) = LiveProbeResult(
                success = success,
                firstByteMs = firstByteMs,
                throughputBytesPerSecond = throughputBytesPerSecond,
                bytesRead = bytesRead,
                failure = failure,
            )
        }

        private fun readRange(url: String): RangeRead {
            val safeHost = LogRedaction.host(url)
            val startedAt = SystemClock.elapsedRealtime()
            return try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BiliApi.UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Range", "bytes=0-${RANGE_BYTES - 1}")
                    .header("Cache-Control", "no-cache")
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    val body = response.body
                    val bytesStartedAt = SystemClock.elapsedRealtime()
                    var firstByteMs = Long.MAX_VALUE
                    val bytes = body?.byteStream()?.use { input ->
                        val output = ByteArrayOutputStream(RANGE_BYTES)
                        val buffer = ByteArray(8 * 1024)
                        val firstRead = input.read(buffer, 0, buffer.size)
                        if (firstRead > 0) {
                            firstByteMs = SystemClock.elapsedRealtime() - startedAt
                            output.write(buffer, 0, firstRead)
                        }
                        while (output.size() < RANGE_BYTES) {
                            val remaining = RANGE_BYTES - output.size()
                            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } ?: ByteArray(0)
                    val elapsedMs = (SystemClock.elapsedRealtime() - bytesStartedAt).coerceAtLeast(1L)
                    if (firstByteMs == Long.MAX_VALUE) {
                        firstByteMs = SystemClock.elapsedRealtime() - startedAt
                    }
                    val success = response.isSuccessful && bytes.isNotEmpty()
                    val failure = when {
                        !response.isSuccessful -> "HTTP ${response.code}"
                        bytes.isEmpty() -> "empty response"
                        else -> ""
                    }
                    RangeRead(
                        success = success,
                        firstByteMs = firstByteMs,
                        throughputBytesPerSecond = bytes.size * 1000L / elapsedMs,
                        bytes = bytes,
                        contentType = body?.contentType()?.toString().orEmpty(),
                        failure = failure,
                    )
                }
            } catch (failure: IOException) {
                LogHub.e(
                    TAG,
                "probe error host=$safeHost ms=${SystemClock.elapsedRealtime() - startedAt} " +
                    "error=${failure.javaClass.simpleName}",
                )
                failedProbe(failure.javaClass.simpleName)
            } catch (failure: Throwable) {
                LogHub.e(TAG, "probe exception host=$safeHost error=${failure.javaClass.simpleName}")
                failedProbe(failure.javaClass.simpleName)
            }
        }

        private fun firstPlaylistUri(bytes: ByteArray): String? =
            String(bytes, Charsets.UTF_8).lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }

        private fun failedProbe(reason: String) = RangeRead(
            success = false,
            firstByteMs = Long.MAX_VALUE,
            throughputBytesPerSecond = 0L,
            bytes = ByteArray(0),
            failure = reason,
        )
    }
}
