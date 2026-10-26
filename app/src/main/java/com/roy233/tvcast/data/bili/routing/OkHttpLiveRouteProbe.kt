package com.roy233.tvcast.data.bili.routing

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.feature.playback.model.LiveProbeResult
import com.roy233.tvcast.feature.playback.routing.LiveRouteProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** 使用 HTTP Range 请求读取直播媒体或 HLS 播放列表的探测端口实现。 */
class OkHttpLiveRouteProbe(
    private val client: OkHttpClient = probeClient,
) : LiveRouteProbe {
    override suspend fun probe(url: String): LiveProbeResult = withContext(Dispatchers.IO) {
        var targetUrl = url
        var firstByteMs: Long? = null
        var lastResponse: RangeRead? = null
        repeat(MAX_PLAYLIST_HOPS) {
            val target = targetUrl.toHttpUrlOrNull()
                ?: return@withContext failedProbe("invalid playlist URL").toResult()
            val response = readRange(target.toString())
            lastResponse = response
            if (firstByteMs == null) firstByteMs = response.firstByteMs
            if (!response.success) return@withContext response.toResult(firstByteMs = firstByteMs ?: response.firstByteMs)
            val playlist = response.contentType.contains("mpegurl", ignoreCase = true) ||
                target.encodedPath.endsWith(".m3u8", ignoreCase = true)
            if (!playlist) return@withContext response.toResult(firstByteMs = firstByteMs ?: response.firstByteMs)
            val child = firstPlaylistUri(response.bytes)
                ?: return@withContext response.toResult(firstByteMs = firstByteMs ?: response.firstByteMs)
            targetUrl = runCatching { URI(target.toString()).resolve(child).toString() }.getOrElse {
                return@withContext response.toResult(firstByteMs = firstByteMs ?: response.firstByteMs)
            }
        }
        lastResponse?.toResult(firstByteMs = firstByteMs ?: Long.MAX_VALUE)
            ?: failedProbe("empty playlist response").toResult()
    }

    private fun readRange(url: String): RangeRead {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", BiliApi.UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Range", "bytes=0-${RANGE_BYTES - 1}")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
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
                        val read = input.read(buffer, 0, minOf(buffer.size, RANGE_BYTES - output.size()))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                val elapsed = (SystemClock.elapsedRealtime() - bytesStartedAt).coerceAtLeast(1L)
                if (firstByteMs == Long.MAX_VALUE) firstByteMs = SystemClock.elapsedRealtime() - startedAt
                RangeRead(
                    success = response.isSuccessful && bytes.isNotEmpty(),
                    firstByteMs = firstByteMs,
                    throughputBytesPerSecond = bytes.size * 1_000L / elapsed,
                    bytes = bytes,
                    contentType = body?.contentType()?.toString().orEmpty(),
                    failure = when {
                        !response.isSuccessful -> "HTTP ${response.code}"
                        bytes.isEmpty() -> "empty response"
                        else -> ""
                    },
                )
            }
        } catch (error: IOException) {
            LogHub.e(TAG, "probe error host=${LogRedaction.host(url)} ms=${SystemClock.elapsedRealtime() - startedAt} error=${error.javaClass.simpleName}")
            failedProbe(error.javaClass.simpleName)
        } catch (error: Throwable) {
            LogHub.e(TAG, "probe exception host=${LogRedaction.host(url)} error=${error.javaClass.simpleName}")
            failedProbe(error.javaClass.simpleName)
        }
    }

    private data class RangeRead(
        val success: Boolean,
        val firstByteMs: Long,
        val throughputBytesPerSecond: Long,
        val bytes: ByteArray,
        val contentType: String = "",
        val failure: String = "",
    ) {
        fun toResult(firstByteMs: Long = this.firstByteMs) = LiveProbeResult(
            success, firstByteMs, throughputBytesPerSecond, bytes.size, failure,
        )
    }

    private companion object {
        const val TAG = "LiveCdn"
        const val RANGE_BYTES = 64 * 1024
        const val MAX_PLAYLIST_HOPS = 3
        const val TIMEOUT_SECONDS = 4L
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS + 2L, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun firstPlaylistUri(bytes: ByteArray): String? = String(bytes, Charsets.UTF_8).lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }

        fun failedProbe(reason: String) = RangeRead(false, Long.MAX_VALUE, 0L, ByteArray(0), failure = reason)
    }
}
