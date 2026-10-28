package com.roy233.tvcast.data.bili.routing

import android.os.SystemClock
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.feature.playback.model.VodProbeResult
import com.roy233.tvcast.feature.playback.routing.VodRouteProbe
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 使用 HTTP Range 请求采样点播媒体响应时间和吞吐量。 */
class OkHttpVodRouteProbe(
    private val client: OkHttpClient = probeClient,
) : VodRouteProbe {
    override suspend fun probe(url: String, range: String): VodProbeResult {
        val request = Request.Builder().url(url)
            .header("User-Agent", BiliApi.UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Range", range)
            .get()
            .build()
        val started = SystemClock.elapsedRealtime()
        return try {
            client.newCall(request).execute().use { response ->
                val firstByteMs = SystemClock.elapsedRealtime() - started
                if (!response.isSuccessful) return VodProbeResult(false, firstByteMs, 0L, 0, "HTTP_${response.code}")
                val input = response.body?.byteStream()
                    ?: return VodProbeResult(false, firstByteMs, 0L, 0, "empty_body")
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < PROBE_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, PROBE_BYTES - total))
                    if (count < 0) break
                    total += count
                }
                val elapsed = (SystemClock.elapsedRealtime() - started - firstByteMs).coerceAtLeast(1L)
                VodProbeResult(
                    success = total > 0,
                    firstByteMs = firstByteMs,
                    throughputBytesPerSecond = total * 1_000L / elapsed,
                    bytesRead = total,
                    failure = if (total > 0) "" else "empty_range",
                )
            }
        } catch (error: Exception) {
            VodProbeResult(false, Long.MAX_VALUE, 0L, 0, error.javaClass.simpleName)
        }
    }

    private companion object {
        const val PROBE_BYTES = 64 * 1024
        const val TIMEOUT_SECONDS = 4L
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS + 1L, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
